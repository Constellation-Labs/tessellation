package io.constellationnetwork.currency.l0.snapshot.programs

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

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataCalculatedState, L0NodeContext}
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.node.IdentifierStorage
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensus
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.domain.snapshot.{PeerSelect, Validator}
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencySnapshotContextFunctions
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, HasherSelector}

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

object Download {
  def make[F[_]: Async: Random](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: CurrencySnapshotConsensus[F],
    peerSelect: PeerSelect[F],
    identifierStorage: IdentifierStorage[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F]
  )(implicit l0NodeContext: L0NodeContext[F]): Download[F] = new Download[F] {

    val logger = Slf4jLogger.getLogger[F]

    val observationOffset = NonNegLong(4L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    def download(implicit hasherSelector: HasherSelector[F]): F[Unit] = {
      implicit val hasher = hasherSelector.getCurrent

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
    }

    def start: F[DownloadResult] =
      peerSelect.select.flatMap {
        p2pClient.currencySnapshot.getLatest.run(_)
      }

    def observe(result: DownloadResult)(implicit hasher: Hasher[F]): F[(DownloadResult, ObservationLimit)] = {
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

    def fetchNextSnapshot(result: DownloadResult)(implicit hasher: Hasher[F]): F[DownloadResult] = {
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
                lastGlobalSnapshotStorage
                  .getLastNSynchronized(lastGlobalSnapshotsSyncConfig.minGlobalSnapshotsToParticipateConsensus.value)
                  .flatMap(lastNGlobalSnapshots =>
                    currencySnapshotContextFns
                      .createContext(
                        CurrencySnapshotContext(currencyAddress, lastContext),
                        lastSnapshot,
                        snapshot,
                        lastNGlobalSnapshots,
                        skipStateProofValidation = true
                      )
                      .handleErrorWith(_ => InvalidChain.raiseError[F, CurrencySnapshotContext])
                  )
              )
              .map(c => (snapshot, c.snapshotInfo))
        }
      }
    }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[CurrencyIncrementalSnapshot]] =
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
