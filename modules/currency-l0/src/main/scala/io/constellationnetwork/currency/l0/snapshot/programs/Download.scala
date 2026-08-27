package io.constellationnetwork.currency.l0.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all.none
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
import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.snapshot.{CurrencySnapshotConsensus, CurrencySnapshotRecoveryStorage}
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.domain.snapshot.{PeerSelect, Validator}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencySnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.RecoveryFallbackEligible
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{CombinedSnapshotCheckpointFileSystemStorage, IdentifierStorage}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, HasherSelector}

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

object Download {
  private[programs] def selectPersistence[F[_]](
    recovery: Boolean,
    sequential: F[Unit],
    recoveryReset: F[Unit]
  ): F[Unit] =
    if (recovery) recoveryReset else sequential

  def make[F[_]: Async: Random](
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: CurrencySnapshotConsensus[F],
    peerSelect: PeerSelect[F],
    identifierStorage: IdentifierStorage[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
    currencySnapshotCleanupStorage: CurrencySnapshotCleanupStorage[F],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    recoveryStorage: CurrencySnapshotRecoveryStorage[F]
  )(implicit l0NodeContext: L0NodeContext[F]): Download[F, CurrencyIncrementalSnapshot] = new Download[F, CurrencyIncrementalSnapshot] {

    val logger = Slf4jLogger.getLogger[F]

    val observationOffset = NonNegLong(4L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    // Recovery observes and validates the same bounded forward chain as an initial download, but
    // persistence must reset the recovered head. A sequential prepend is correct only for an empty
    // initial node or the immediate next ordinal; it rejects the non-contiguous jump produced by a
    // follower that catches up from an older accepted head.
    def recoveryDownload(implicit hasherSelector: HasherSelector[F]): F[Unit] = run(recovery = true)

    def download(implicit hasherSelector: HasherSelector[F]): F[Unit] = run(recovery = false)

    private def run(recovery: Boolean)(implicit hasherSelector: HasherSelector[F]): F[Unit] = {
      implicit val hasher = hasherSelector.getCurrent

      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(start)
        .flatMap(observe)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result

          logger.info(s"[Download] Cleanup for snapshots greater than ${snapshot.ordinal}") >>
            currencySnapshotCleanupStorage.cleanupAbove(snapshot.ordinal) >>
            combinedSnapshotCheckpointFileSystemStorage.deleteAbove(snapshot.ordinal) >>
            identifierStorage.get.flatMap { currencyAddress =>
              val snapshotContext = CurrencySnapshotContext(currencyAddress, context)
              val sequentialPersistence =
                eventMempool.clear >>
                  logger.info("[Download] Cleared event mempool for initial download") >>
                  snapshotStorage.prepend(snapshot, context).flatMap { prepended =>
                    if (!prepended)
                      (new Exception(s"Failed to prepend currency snapshot ordinal=${snapshot.ordinal} to storage"))
                        .raiseError[F, Unit]
                    else
                      Applicative[F].unit
                  } >>
                  fetchAndSetCalculatedState(snapshot)

              Download.selectPersistence(
                recovery,
                sequentialPersistence,
                recoveryStorage.synchronize(snapshot, snapshotContext)
              ) >> consensus.manager.startFacilitatingAfterDownload(
                observationLimit,
                snapshot,
                snapshotContext,
                isRecovery = recovery
              )
            }
        }
    }

    private def fetchAndSetCalculatedState(snapshot: Signed[CurrencyIncrementalSnapshot])(implicit hasher: Hasher[F]): F[Unit] =
      maybeDataApplication.map { da =>
        implicit val d = da.calculatedStateDecoder

        val retryPolicy = RetryPolicies.limitRetries[F](3).join(RetryPolicies.exponentialBackoff(2.seconds))

        retryingOnAllErrors[(SnapshotOrdinal, DataCalculatedState)](
          policy = retryPolicy,
          onError = (err: Throwable, retryDetails: RetryDetails) =>
            logger.warn(err)(s"Error fetching calculated state (attempt=${retryDetails.retriesSoFar}), selecting new peer")
        ) {
          clusterStorage.getResponsivePeers
            .map(NodeState.ready)
            .map(_.toList)
            .flatMap(Random[F].shuffleList)
            .flatMap {
              case Nil =>
                (new Exception(s"No peers to fetch off-chain state from")).raiseError[F, (SnapshotOrdinal, DataCalculatedState)]
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
        }.flatMap { case (ordinal, calculatedState) => da.setCalculatedState(ordinal, calculatedState) }.void
      }.getOrElse(Applicative[F].unit)

    def start: F[DownloadResult] = {
      val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
      retryingOnAllErrors[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)](
        policy = retryPolicy,
        onError = (err: Throwable, retryDetails: RetryDetails) =>
          logger.error(err)(s"Error when trying to fetch latest metadata (attempt=${retryDetails.retriesSoFar}), selecting new peer")
      ) {
        peerSelect.select.flatMap {
          p2pClient.currencySnapshot.getLatest.run(_)
        }
      }
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
      def retryPolicy = constantDelay(fetchSnapshotDelayBetweenTrials).join(limitRetries(30))

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
                  .createContext(
                    CurrencySnapshotContext(currencyAddress, lastContext),
                    lastSnapshot,
                    snapshot,
                    getGlobalSnapshotByOrdinal
                  )
                  .handleErrorWith(_ => InvalidChain.raiseError[F, CurrencySnapshotContext])
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

  case object InvalidChain extends NoStackTrace with RecoveryFallbackEligible
}
