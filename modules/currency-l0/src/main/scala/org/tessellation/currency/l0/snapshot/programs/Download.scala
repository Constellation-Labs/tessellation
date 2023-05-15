package org.tessellation.currency.l0.snapshot.programs

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

import org.tessellation.currency.l0.http.P2PClient
import org.tessellation.currency.l0.snapshot.CurrencySnapshotContext
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.domain.snapshot.programs.Download
import org.tessellation.sdk.infrastructure.snapshot.{CurrencySnapshotContextFunctions, SnapshotConsensus}
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

object Download {
  def make[F[_]: Async: KryoSerializer: Random](
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, _],
    peerSelect: PeerSelect[F]
  ): Download[F] = new Download[F] {

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

          consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context)
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
          currencySnapshotContextFns
            .createContext(lastContext, lastSnapshot.value, snapshot)
            .handleErrorWith(_ => InvalidChain.raiseError[F, CurrencySnapshotContext])
            .map((snapshot, _))
        }
      }
    }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal): F[Signed[CurrencyIncrementalSnapshot]] =
      clusterStorage.getPeers
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
