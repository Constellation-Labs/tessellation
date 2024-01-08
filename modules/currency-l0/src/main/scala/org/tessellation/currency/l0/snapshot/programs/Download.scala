package org.tessellation.currency.l0.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.semigroup._
import cats.syntax.show._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, DataCalculatedState, L0NodeContext}
import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.snapshot.programs.Download
import org.tessellation.node.shared.domain.snapshot.{PeerSelect, Validator}
import org.tessellation.node.shared.infrastructure.snapshot.{CurrencySnapshotContextFunctions, SnapshotConsensus}
import org.tessellation.schema._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher}

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

object Download {
  def make[F[_]: Async: Hasher: Random](
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, CurrencyIncrementalSnapshot, CurrencySnapshotContext, _],
    peerSelect: PeerSelect[F],
    identifierStorage: IdentifierStorage[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]]
  )(implicit l0NodeContext: L0NodeContext[F]): Download[F] = new Download[F] {

    val logger = Slf4jLogger.getLogger[F]

    val observationOffset = NonNegLong(4L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    def download: F[Unit] =
      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(start)
        .flatMap(observe)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result

          def setCalculatedState = maybeDataApplication.map { da =>
            implicit val d = da.calculatedStateDecoder

            clusterStorage.getResponsivePeers
              .map(NodeState.ready)
              .map(_.toList)
              .flatMap(Random[F].shuffleList)
              .flatMap {
                case Nil => (new Exception(s"No peers to fetch off-chain state from")).raiseError[F, (SnapshotOrdinal, DataCalculatedState)]
                case peer :: _ => p2pClient.dataApplication.getCalculatedState.run(peer)
              }
              .flatTap {
                case (_, calculatedState) =>
                  da.hashCalculatedState(calculatedState).flatMap { calculatedStateHash =>
                    (new Exception(s"Downloaded calculated state does not match the proof stored in snapshot")
                      .raiseError[F, Unit])
                      .unlessA(snapshot.dataApplication.map(_.calculatedStateProof) === calculatedStateHash.some)
                  }
              }
              .flatMap { case (ordinal, calculatedState) => da.setCalculatedState(ordinal, calculatedState) }
          }.getOrElse(Applicative[F].unit)

          setCalculatedState >> identifierStorage.get.flatMap { currencyAddress =>
            consensus.manager
              .startFacilitatingAfterDownload(observationLimit, snapshot, CurrencySnapshotContext(currencyAddress, context))
          }
        }

    def start: F[DownloadResult] =
      peerSelect.select.flatMap {
        p2pClient.currencySnapshot.getLatest.run(_)
      }

    def observe(result: DownloadResult): F[(DownloadResult, ObservationLimit)] = {
      val (lastSnapshot, _) = result

      val observationLimit = SnapshotOrdinal(lastSnapshot.ordinal.value |+| observationOffset)

      def go(result: DownloadResult): F[DownloadResult] = {
        val (lastSnapshot, _) = result

        if (lastSnapshot.ordinal === observationLimit) {
          result.pure[F]
        } else fetchNextSnapshot(result) >>= go
      }

      consensus.manager.registerForConsensus(observationLimit) >>
        go(result).map((_, observationLimit))
    }

    def fetchNextSnapshot(result: DownloadResult): F[DownloadResult] = {
      def retryPolicy = constantDelay(fetchSnapshotDelayBetweenTrials)

      def isWorthRetrying(err: Throwable): F[Boolean] = err match {
        case CannotFetchSnapshot | InvalidChain => true.pure[F]
        case _                                  => false.pure[F]
      }

      retryingOnSomeErrors(retryPolicy, isWorthRetrying, retry.noop[F, Throwable]) {
        val (lastSnapshot, lastContext) = result

        fetchSnapshot(none, lastSnapshot.ordinal.next).flatMap { snapshot =>
          lastSnapshot.toHashed[F].flatMap { hashed =>
            Applicative[F].unlessA {
              Validator.isNextSnapshot(hashed, snapshot.value)
            }(InvalidChain.raiseError[F, Unit])
          } >>
            identifierStorage.get
              .flatMap(currencyAddress =>
                currencySnapshotContextFns
                  .createContext(CurrencySnapshotContext(currencyAddress, lastContext), lastSnapshot, snapshot)
                  .handleErrorWith(_ => InvalidChain.raiseError[F, CurrencySnapshotContext])
              )
              .map(c => (snapshot, c.snapshotInfo))
        }
      }
    }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal): F[Signed[CurrencyIncrementalSnapshot]] =
      clusterStorage.getResponsivePeers
        .map(NodeState.ready)
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.info(s"Download currency snapshot hash=${hash.show}, ordinal=${ordinal.show}")
        }
        .flatMap { peers =>
          type Success = Signed[CurrencyIncrementalSnapshot]
          type Result = Option[Success]
          type Agg = (List[Peer], Result)

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.currencySnapshot
                .get(ordinal)
                .run(peer)
                .flatMap(_.toHashed[F])
                .map(_.some)
                .handleError(_ => none[Hashed[CurrencyIncrementalSnapshot]])
                .map {
                  case Some(snapshot) if hash.forall(_ === snapshot.hash) => snapshot.signed.some.asRight[Agg]
                  case _                                                  => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchSnapshot.raiseError[F, Signed[CurrencyIncrementalSnapshot]]
        }

  }

  case object CannotFetchSnapshot extends NoStackTrace
  case object InvalidChain extends NoStackTrace
}
