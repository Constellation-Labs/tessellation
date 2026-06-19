package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, MonadError, Parallel}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.{GlobalSnapshotConsensus, GlobalSnapshotContext}
import io.constellationnetwork.ext.cats.kernel.PartialPrevious
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.cluster.programs.Joining
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.programs.{Download, SnapshotFailure}
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.snapshot.{PeerSelect, Validator}
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.gossip.event.RecoveryPeerHint
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.RecoveryFallbackEligible
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.CombinedSnapshotCheckpointFileSystemStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{L0Peer, Peer}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.validator.StateProofValidator

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Json
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

case class ObserveDeadlineExceeded(currentOrdinal: SnapshotOrdinal, targetOrdinal: SnapshotOrdinal)
    extends RuntimeException(
      s"Observe deadline exceeded: stuck at $currentOrdinal, target was $targetOrdinal. Re-triggering recovery."
    )
    with NoStackTrace

/** Raised when recovery cannot converge to within the target lag of the cluster tip before exhausting the iteration or wall-clock budget.
  * Re-raised so the outer handler transitions the node back to `WaitingForDownload` and `DownloadDaemon` schedules a fresh attempt.
  */
case class RecoveryConvergenceFailed(
  observed: SnapshotOrdinal,
  clusterTip: SnapshotOrdinal,
  lag: Long,
  iterations: Int,
  elapsed: FiniteDuration
) extends RuntimeException(
      s"Recovery did not converge: observed=$observed, clusterTip=$clusterTip, lag=$lag ordinals after " +
        s"$iterations iterations / ${elapsed.toSeconds}s. Re-triggering recovery."
    )
    with NoStackTrace

/** Raised when a backward (rollback) recovery would delete local snapshots above the network tip, but the rollback target `(ordinal, hash)`
  * is not corroborated by a strict majority of Ready peers. Failing closed prevents an irreversible `deleteAbove` on an unverified or
  * minority tip. Re-raised so the outer handler retries: a legitimate cluster-wide rollback corroborates once enough peers report the same
  * target, while a lagging or minority source never reaches a majority.
  */
case class RollbackTargetNotCorroborated(
  target: SnapshotOrdinal,
  responders: Int
) extends RuntimeException(
      s"Rollback target ordinal=$target not corroborated by a strict majority of $responders Ready-peer tips; " +
        s"refusing destructive deleteAbove. Re-triggering recovery."
    )
    with NoStackTrace

object Download {

  /** Per-Ready-peer advertised tip. */
  private[snapshot] final case class PeerTip(ordinal: SnapshotOrdinal, hash: Hash)

  /** Categorical label for the `dag_download_*_outcome_total{outcome}` counter family.
    *
    * Producers (start path, observe path, both full and recovery) classify their result into one of these cases. The Prometheus label is
    * `outcome.label`; `isUnclassified` decides whether to ALSO emit a warning log carrying the underlying exception class and message.
    *
    * Replaces an earlier string-based encoding where the producer returned a `String` and the caller branched on
    * `outcome.startsWith("other_")`. The string check could silently break if the prefix was renamed; this ADT shifts the discriminator
    * into the type system.
    */
  sealed trait DownloadOutcome {
    def label: String
    def isUnclassified: Boolean = false
  }

  object DownloadOutcome {
    case object Success extends DownloadOutcome { val label = "success" }
    case object Shortcut extends DownloadOutcome { val label = "shortcut" }
    case object ForwardObserveSuccess extends DownloadOutcome { val label = "forward_observe_success" }

    case object DeadlineExceeded extends DownloadOutcome { val label = "deadline_exceeded" }
    case object StateProofInvalid extends DownloadOutcome { val label = "state_proof_invalid" }
    case object ChainInvalid extends DownloadOutcome { val label = "chain_invalid" }
    case object HashOrdinalMismatch extends DownloadOutcome { val label = "hash_ordinal_mismatch" }
    case object FirstIncrementalMissing extends DownloadOutcome { val label = "first_incremental_missing" }
    case object FetchSnapshotFailed extends DownloadOutcome { val label = "fetch_snapshot_failed" }
    case object FetchGenesisFailed extends DownloadOutcome { val label = "fetch_genesis_failed" }
    case object StartTimedOut extends DownloadOutcome { val label = "start_timed_out" }
    case object CleanupIncomplete extends DownloadOutcome { val label = "cleanup_incomplete" }
    case object BalanceArithmeticAllowSpends extends DownloadOutcome { val label = "balance_arithmetic_allow_spends" }
    case object BalanceArithmeticTokenLocks extends DownloadOutcome { val label = "balance_arithmetic_token_locks" }
    case object BalanceArithmeticSpendTxns extends DownloadOutcome { val label = "balance_arithmetic_spend_txns" }
    case object TokenUnlockError extends DownloadOutcome { val label = "token_unlock_error" }

    final case class Unclassified(cls: String) extends DownloadOutcome {
      val label: String = s"other_$cls"
      override val isUnclassified: Boolean = true
    }
  }

  /** Minimum number of Ready peer responses required before trusting a caught-up shortcut.
    *
    * Set to 1: in a rollback-lead topology (one rollback node + N validators), the validators need to transition through Observing to Ready
    * before they become Ready peers. Until at least one validator transitions, the only Ready peer the validators can query is the single
    * rollback-lead. Requiring 2 responses would be an architectural mismatch that deadlocks the exact recovery path this predicate is
    * supposed to enable.
    *
    * Single-peer safety is provided by the hash-identity check at local ordinal (condition 4 of `chooseObservationLimit`). If the single
    * responding peer is on a different chain at our local ordinal, its hash won't match ours and the shortcut is rejected. A dishonest
    * rollback-lead peer would be catastrophic regardless — it is the chain's trust root.
    */
  private[snapshot] val minReadyQuorum: Int = 1

  /** Minimum number of Ready peers that must agree on the exact `(ordinal, hash)` rollback target before a backward (destructive) recovery
    * is allowed to `deleteAbove` it. Unlike `minReadyQuorum` (which gates the non-destructive forward-observe shortcut and is intentionally
    * 1), a destructive rollback must not be authorized by a single source peer, so this floor is 2 and is combined with a strict-majority
    * check (see the corroboration gate in `recoveryStart`). Forward / same-ordinal recovery is not gated -- it deletes nothing above our
    * tip.
    */
  private[snapshot] val minRollbackCorroborators: Int = 2

  /** Decide the observe-loop target ordinal.
    *
    * Default behavior: require observing `currentOrdinal + observationOffset` (i.e. one newer snapshot) before exiting observe. This is the
    * safe catch-up path used whenever peers are ahead of us OR the evidence from peers is inconclusive.
    *
    * Shortcut: when the cluster is at a stable tip and we're already caught up, there will never BE a newer snapshot to observe, so the
    * default path would loop forever. In that case we set the target to `currentOrdinal` so `observeWithLimit` returns immediately and the
    * downstream `initFromDownload -> WaitingForReady -> Ready` flow can begin.
    *
    * Shortcut is taken iff ALL hold:
    *   1. At least `minReadyQuorum` Ready peers responded with a (ordinal, hash) tip. 2. A strict majority (> N/2 of responders) agree on
    *      the same (ordinal, hash) pair. 3. The majority ordinal is ≤ our local ordinal. 4. If the majority ordinal equals our local
    *      ordinal, the majority hash equals our local hash (prevents a running-fork scenario where peers are "at our ordinal" but on a
    *      different chain).
    *
    * Data source for peer tips: `SnapshotRoutes:/global-snapshots/latest/metadata`, which is `whenNodeReady`-gated on the responder. Since
    * alpha.64 the gate accepts both `NodeState.Ready` and `NodeState.WaitingForReady`, with the body falling back to
    * `lastNGlobalSnapshotStorage` when production head is empty (so a recovering source node serves its recovery tip).
    */
  private[snapshot] def chooseObservationLimit(
    currentOrdinal: SnapshotOrdinal,
    currentHash: Hash,
    readyPeerTips: List[PeerTip],
    observationOffset: NonNegLong
  ): SnapshotOrdinal = {
    val fallback = SnapshotOrdinal(currentOrdinal.value |+| observationOffset)
    if (readyPeerTips.size < minReadyQuorum) fallback
    else {
      val grouped = readyPeerTips.groupBy(t => (t.ordinal, t.hash))
      val ((majorityOrdinal, majorityHash), majorityGroup) = grouped.maxBy(_._2.size)
      val hasStrictMajority = majorityGroup.size > readyPeerTips.size / 2
      val majorityAtOrBehindUs = majorityOrdinal <= currentOrdinal
      val hashAgreesWhenMajorityAtOurTip =
        majorityOrdinal =!= currentOrdinal || majorityHash === currentHash
      if (hasStrictMajority && majorityAtOrBehindUs && hashAgreesWhenMajorityAtOurTip) currentOrdinal
      else fallback
    }
  }

  def make[F[_]: Async: Parallel: Random: KryoSerializer: JsonSerializer: Metrics](
    snapshotStorage: SnapshotDownloadStorage[F],
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: GlobalSnapshotConsensus[F],
    peerSelect: PeerSelect[F],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      GlobalIncrementalSnapshot,
      GlobalSnapshotInfo
    ],
    mptStore: MptStore[F, GlobalStateKey],
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    globalSnapshotConsensusStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    joining: Joining[F],
    recoveryPeerHint: RecoveryPeerHint[F]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): Download[F, GlobalIncrementalSnapshot] = new Download[F, GlobalIncrementalSnapshot] {

    val logger = Slf4jLogger.getLogger[F]

    private val validator = StateProofValidator.forGlobal(Some(mptStore.underlying))

    val minBatchSizeToStartObserving: Long = 1L
    val observationOffset = NonNegLong(1L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

    // Outer watchdog for Download.start (both full path and recovery path). Picked generously:
    // a fresh-join full download from genesis can take several minutes through validateChain +
    // MPT trie build (~890k entries observed on testnet). Anything longer than this is almost
    // certainly a hung fiber, not a slow legitimate download. Wraps `start` in timeoutTo so a
    // hang raises DownloadStartTimedOut, which fires the .onError instrumentation and lets the
    // FSM revert state to WaitingForDownload for retry.
    val downloadStartMaxDuration: FiniteDuration = 10.minutes

    // Upper bound on the iterations validateChain spends searching for a valid persisted
    // (snapshot, info) pair when the local state has drifted. Each iteration discards one
    // mismatched pair and asks getHighestSnapshotInfoOrdinal for the next-lower candidate;
    // 200 caps the descent so a fully-corrupted local state raises instead of looping.
    private val maxPersistedSearchAttempts: Int = 200

    type DownloadResult = (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    private def fetchSnapshotByOrdinal(implicit hasherSelector: HasherSelector[F]) = (ordinal: SnapshotOrdinal) =>
      hasherSelector.withCurrent { implicit hasher =>
        fetchSnapshot(none, ordinal).flatMap(_.toHashed.map(_.some))
      }

    private def setInitialSnapshots(
      hashedSnapshot: Hashed[GlobalIncrementalSnapshot],
      context: GlobalSnapshotContext
    )(implicit hasherSelector: HasherSelector[F]): F[Unit] =
      for {
        _ <- hasherSelector.withCurrent { implicit hasher =>
          lastNGlobalSnapshotStorage.setInitialFetchingGL0(
            hashedSnapshot,
            context,
            none,
            Some((hash, ordinal) => fetchSnapshot(hash, ordinal)(hasher))
          )
        }
        _ <- lastGlobalSnapshotStorage.setInitial(hashedSnapshot, context)
      } yield ()

    private def updateSnapshots(
      hashedSnapshot: Hashed[GlobalIncrementalSnapshot],
      context: GlobalSnapshotContext
    ): F[Unit] =
      for {
        _ <- lastNGlobalSnapshotStorage.set(hashedSnapshot, context)
        _ <- lastGlobalSnapshotStorage.set(hashedSnapshot, context)
      } yield ()

    def updateStoragesWithDownloadedSnapshot(
      snapshot: Signed[GlobalIncrementalSnapshot],
      context: GlobalSnapshotContext
    )(implicit hasherSelector: HasherSelector[F]): F[Unit] =
      for {
        hashedSnapshot <- hasherSelector.withCurrent(implicit hs => snapshot.toHashed)
        alreadyInitializedStorage <- lastNGlobalSnapshotStorage.get
        _ <-
          if (alreadyInitializedStorage.isEmpty) setInitialSnapshots(hashedSnapshot, context)
          else updateSnapshots(hashedSnapshot, context)
        _ <- Metrics[F].updateGauge("dag_download_latest_downloaded_ordinal", snapshot.ordinal.value.value.toDouble)
        // Emit fresh metrics when a snapshot is accepted via download, not just via consensus.
        // Without this, a peer that is catching up via download keeps stale ordinal/signer_count
        // metrics in Grafana until it completes a full consensus round. Pair with last_updated_epoch
        // so dashboards can filter stale peers via `time() - last_updated_epoch < freshness`.
        _ <- Async[F].realTimeInstant.map(_.getEpochSecond.toDouble).flatMap { epochSec =>
          Metrics[F].updateGauge("dag_global_snapshot_last_updated_epoch", epochSec)
        }
        _ <- Metrics[F].updateGauge("dag_global_snapshot_ordinal", snapshot.ordinal.value.value.toDouble)
        _ <- Metrics[F].updateGauge("dag_global_snapshot_height", snapshot.height.value.value.toDouble)
        _ <- Metrics[F].updateGauge("dag_global_snapshot_signature_count", snapshot.proofs.size.toDouble)
      } yield ()

    def download(implicit hasherSelector: HasherSelector[F]): F[Unit] = {
      val guardedStart =
        recordDownloadPhase("full", "start_entered") >>
          Async[F].timeoutTo(
            start,
            downloadStartMaxDuration,
            DownloadStartTimedOut.raiseError[F, DownloadResult]
          )
      val instrumentedStart = guardedStart
        .flatTap(_ => recordStartOutcome("full", DownloadOutcome.Success) >> recordDownloadPhase("full", "start_success"))
        .onError {
          case err =>
            val outcome = classifyStartError(err)
            val maybeLog =
              if (outcome.isUnclassified) logUnclassifiedStartError(err) else Async[F].unit
            maybeLog >> recordStartOutcome("full", outcome)
        }

      def instrumentedObserve(result: DownloadResult): F[(DownloadResult, ObservationLimit)] =
        recordDownloadPhase("full", "observe_entered") >>
          observe(result)
            .flatTap(_ => recordObserveOutcome("full", DownloadOutcome.Success) >> recordDownloadPhase("full", "observe_success"))
            .onError {
              case err =>
                val outcome = classifyObserveError(err)
                val maybeLog =
                  if (outcome.isUnclassified) logUnclassifiedObserveError(err) else Async[F].unit
                maybeLog >> recordObserveOutcome("full", outcome)
            }

      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(instrumentedStart)
        .flatMap(instrumentedObserve)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result
          for {
            _ <- consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context)
            _ <- recordDownloadPhase("full", "facilitate_enqueued")
          } yield ()
        }
        .onError(logger.error(_)("Unexpected failure during download!"))
        .handleErrorWith { err =>
          // If download fails after start() succeeded (e.g. during observe() or startFacilitatingAfterDownload),
          // the node may be stuck in WaitingForObserving/Observing with nobody to retry.
          // Revert to WaitingForDownload so the DownloadDaemon can retry from scratch.
          nodeStorage.getNodeState.flatMap {
            case state if state =!= NodeState.WaitingForDownload && state =!= NodeState.Ready =>
              logger.warn(s"Download failed in state=$state, reverting to WaitingForDownload for retry") >>
                nodeStorage.setNodeState(NodeState.WaitingForDownload)
            case _ =>
              Async[F].unit // Already in WaitingForDownload (start failed) or Ready (someone else recovered)
          } >> err.raiseError[F, Unit]
        }
    }

    /** Incremental recovery download: fetches only the gap between local tip and network tip.
      *
      * Unlike full download, this path:
      *   - Does NOT clear in-memory caches (lastNGlobalSnapshotStorage, lastGlobalSnapshotStorage)
      *   - Does NOT clear the event mempool — forked events from a minority fork will be rejected by consensus validation when proposed, so
      *     stale mempool entries are harmless
      *   - Does NOT clear the MPT store
      *   - Observes exactly one round (waits for the next snapshot) before facilitating
      *   - Uses setForRecovery on lastNGlobalSnapshotStorage (sets single snapshot, no backfill)
      *
      * Falls back to full download if no local persisted state exists (fresh join scenario).
      *
      * Recovery download still goes through the same state machine transitions: WaitingForDownload → DownloadInProgress →
      * WaitingForObserving → Observing → WaitingForReady → Ready. It observes exactly one round (waits for the next snapshot to appear) to
      * ensure the node starts facilitating at the beginning of a round rather than mid-flight, avoiding a race condition where the node
      * joins a round already in progress and misses declarations/proposals.
      */
    // Classify a download start/observe failure into a `DownloadOutcome` for Prometheus labeling.
    // Recovery FSM bounces back to WaitingForDownload on any error, so the precise cause is invisible
    // from source-node logs alone. Unclassified exceptions surface as `Unclassified(SimpleClassName)`
    // so the metric label names the exception type without needing log access.
    private def classifyStartError(err: Throwable): DownloadOutcome = err match {
      case _: SnapshotFailure.BalanceArithmeticError.AllowSpends       => DownloadOutcome.BalanceArithmeticAllowSpends
      case _: SnapshotFailure.BalanceArithmeticError.TokenLocks        => DownloadOutcome.BalanceArithmeticTokenLocks
      case _: SnapshotFailure.BalanceArithmeticError.SpendTransactions => DownloadOutcome.BalanceArithmeticSpendTxns
      case _: SnapshotFailure.TokenUnlockGenerationFailed              => DownloadOutcome.TokenUnlockError
      case _: SnapshotFailure.CleanupIncomplete                        => DownloadOutcome.CleanupIncomplete
      case InvalidStateProof(_)                                        => DownloadOutcome.StateProofInvalid
      case InvalidChain                                                => DownloadOutcome.ChainInvalid
      case HashAndOrdinalMismatch                                      => DownloadOutcome.HashOrdinalMismatch
      case FirstIncrementalNotFound                                    => DownloadOutcome.FirstIncrementalMissing
      case CannotFetchSnapshot                                         => DownloadOutcome.FetchSnapshotFailed
      case CannotFetchGenesisSnapshot                                  => DownloadOutcome.FetchGenesisFailed
      case DownloadStartTimedOut                                       => DownloadOutcome.StartTimedOut
      case other                                                       => DownloadOutcome.Unclassified(other.getClass.getSimpleName)
    }

    private def classifyObserveError(err: Throwable): DownloadOutcome = err match {
      case _: ObserveDeadlineExceeded                                  => DownloadOutcome.DeadlineExceeded
      case _: SnapshotFailure.BalanceArithmeticError.AllowSpends       => DownloadOutcome.BalanceArithmeticAllowSpends
      case _: SnapshotFailure.BalanceArithmeticError.TokenLocks        => DownloadOutcome.BalanceArithmeticTokenLocks
      case _: SnapshotFailure.BalanceArithmeticError.SpendTransactions => DownloadOutcome.BalanceArithmeticSpendTxns
      case _: SnapshotFailure.TokenUnlockGenerationFailed              => DownloadOutcome.TokenUnlockError
      case CannotFetchSnapshot                                         => DownloadOutcome.FetchSnapshotFailed
      case InvalidChain                                                => DownloadOutcome.ChainInvalid
      case other                                                       => DownloadOutcome.Unclassified(other.getClass.getSimpleName)
    }

    // Log the exception when an outcome is `Unclassified` so source-node and community-peer log
    // captures retain the underlying type + message even without log scraping.
    private def logUnclassifiedStartError(err: Throwable): F[Unit] =
      logger.warn(err)(s"[Download] full-path start unclassified error class=${err.getClass.getName} message=${err.getMessage}")

    private def logUnclassifiedObserveError(err: Throwable): F[Unit] =
      logger.warn(err)(s"[Download] observe unclassified error class=${err.getClass.getName} message=${err.getMessage}")

    private def recordStartOutcome(path: String, outcome: DownloadOutcome): F[Unit] =
      Metrics[F].incrementCounter(
        "dag_download_start_outcome_total",
        Seq(
          Metrics.unsafeLabelName("path") -> path,
          Metrics.unsafeLabelName("outcome") -> outcome.label
        )
      )

    private def recordObserveOutcome(path: String, outcome: DownloadOutcome): F[Unit] =
      Metrics[F].incrementCounter(
        "dag_download_observe_outcome_total",
        Seq(
          Metrics.unsafeLabelName("path") -> path,
          Metrics.unsafeLabelName("outcome") -> outcome.label
        )
      )

    private def recordDownloadPhase(path: String, phase: String): F[Unit] =
      Metrics[F].incrementCounter(
        "dag_download_join_phase_total",
        Seq(
          Metrics.unsafeLabelName("path") -> path,
          Metrics.unsafeLabelName("phase") -> phase
        )
      )

    private def recordPeerTipAlignment(
      path: String,
      localOrdinal: SnapshotOrdinal,
      localHash: Hash,
      readyPeerTips: List[PeerTip],
      observationLimit: SnapshotOrdinal
    ): F[Unit] = {
      val grouped = readyPeerTips.groupBy(t => (t.ordinal, t.hash))
      val majority = grouped.toList.sortBy {
        case ((ordinal, hash), peers) =>
          (-peers.size, ordinal.value.value, hash.value)
      }.headOption
      val (majorityOrdinal, majorityHash, majorityCount, ordinalGap) = majority match {
        case Some(((ordinal, hash), peers)) =>
          (ordinal, hash, peers.size, ordinal.value.value - localOrdinal.value.value)
        case None =>
          (localOrdinal, localHash, 0, 0L)
      }
      val tags = Seq(Metrics.unsafeLabelName("path") -> path)

      Metrics[F].updateGauge("dag_download_ready_peer_tip_count", readyPeerTips.size.toLong, tags) >>
        Metrics[F].updateGauge("dag_download_ready_peer_tip_majority_count", majorityCount.toLong, tags) >>
        Metrics[F].updateGauge("dag_download_ready_peer_tip_majority_ordinal", majorityOrdinal.value.value.toDouble, tags) >>
        Metrics[F].updateGauge("dag_download_ready_peer_tip_gap_ordinals", ordinalGap.toDouble, tags) >>
        Metrics[F].updateGauge("dag_download_observation_limit_ordinal", observationLimit.value.value.toDouble, tags) >>
        logger.info(
          s"[DownloadJoin] path=$path local=${localOrdinal.show} localHash=${localHash.value.take(8)} " +
            s"readyPeerTips=${readyPeerTips.size} majorityCount=$majorityCount " +
            s"majority=${majorityOrdinal.show}/${majorityHash.value.take(8)} gap=$ordinalGap " +
            s"observationLimit=${observationLimit.show}"
        )
    }

    private def recordConvergenceOutcome(outcome: String): F[Unit] =
      Metrics[F].incrementCounter(
        "dag_recovery_convergence_iterations_total",
        Seq(Metrics.unsafeLabelName("outcome") -> outcome)
      )

    private def recordRecoveryPhase(phase: String, outcome: String): F[Unit] =
      Metrics[F].incrementCounter(
        "dag_recovery_phase_total",
        Seq(
          Metrics.unsafeLabelName("phase") -> phase,
          Metrics.unsafeLabelName("outcome") -> outcome
        )
      )

    private def recoveryDirection(local: SnapshotOrdinal, remote: SnapshotOrdinal): String =
      if (remote > local) "forward"
      else if (remote === local) "same_ordinal"
      else "rollback"

    def recoveryDownload(implicit hasherSelector: HasherSelector[F]): F[Unit] = {
      def getLatestMetadataWithPeer: F[(L0Peer, SnapshotMetadata)] = {
        val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
        retryingOnAllErrors[(L0Peer, SnapshotMetadata)](
          policy = retryPolicy,
          onError = (err: Throwable, details: RetryDetails) =>
            logger.error(err)(s"[RecoveryDownload] Error fetching metadata (attempt=${details.retriesSoFar})")
        ) {
          // selectForRecovery widens the candidate pool to include Observing peers when no Ready peer is
          // available. Mirrors the Ready -> Observing fallback used by
          // StateTransitions.fetchOutcomeFromCluster. Doesn't fully solve the alpha.40 cascade where ALL
          // peers were in WaitingForDownload, but covers the partial case where some peers reached
          // Observing while others were still in download.
          // #8: bias recovery source selection toward the fork-recovery majority hint when present (it only
          // narrows within the validated candidate set; see PeerSelect.selectForRecovery).
          recoveryPeerHint.getPreferredPeers
            .flatMap(hint => peerSelect.selectForRecovery(hint.getOrElse(Set.empty)))
            .flatMap(peer => p2pClient.globalSnapshot.getLatestMetadata.run(peer).tupleLeft(peer))
        }
      }

      def getLatestMetadata: F[SnapshotMetadata] =
        getLatestMetadataWithPeer.map(_._2)

      def recoveryStart: F[DownloadResult] = {
        val body =
          for {
            _ <- recordDownloadPhase("recovery", "start_entered")
            localHead <- lastNGlobalSnapshotStorage.get
            (sourcePeer, metadata) <- getLatestMetadataWithPeer
            localOrdinal = localHead.map(_.ordinal)
            direction = localOrdinal.fold("unknown")(recoveryDirection(_, metadata.ordinal))
            _ <- logger.info(
              s"[RecoveryDownload] Starting incremental recovery. Network tip: ordinal=${metadata.ordinal.show}, hash=${metadata.hash.show}, " +
                s"source=${sourcePeer.ip}:${sourcePeer.port}, localOrdinal=${localOrdinal.map(_.show).getOrElse("none")}, " +
                s"direction=$direction"
            )
            _ <- recordRecoveryPhase("start", direction)
            // #9 directional rollback gate: a backward recovery (network tip BELOW our local ordinal) deletes
            // local snapshots above the tip -- irreversible. Require a strict majority of Ready peers to
            // corroborate the EXACT (ordinal, hash) target, with at least `minRollbackCorroborators` agreeing,
            // so a single (lagging or minority) source peer cannot authorize destroying local state. Forward /
            // same-ordinal recovery is NOT gated: `deleteAbove(metadata.ordinal)` removes nothing above our tip
            // there. Fail closed on no corroboration -- raise so the outer recovery loop retries: a legitimate
            // cluster-wide rollback corroborates once peers come up; a minority-fork source never reaches a
            // majority and the node keeps retrying (eventually force-leaving via the recovery-attempt counter).
            _ <- Async[F].whenA(localOrdinal.exists(metadata.ordinal < _)) {
              getReadyPeerTips.flatMap { tips =>
                val corroborators = tips.count(t => t.ordinal === metadata.ordinal && t.hash === metadata.hash)
                val corroborated = corroborators >= minRollbackCorroborators && corroborators > tips.size / 2
                if (corroborated)
                  logger.info(
                    s"[RecoveryDownload] Rollback target ordinal=${metadata.ordinal.show} hash=${metadata.hash.show} " +
                      s"corroborated by $corroborators/${tips.size} Ready peers"
                  ) >> recordRecoveryPhase("rollback_corroborated", direction)
                else
                  logger.warn(
                    s"[RecoveryDownload] Rollback target ordinal=${metadata.ordinal.show} NOT corroborated " +
                      s"($corroborators/${tips.size} Ready peers agree; need a strict majority and >= $minRollbackCorroborators); " +
                      s"refusing destructive deleteAbove and re-triggering recovery"
                  ) >>
                    Metrics[F].incrementCounter("dag_recovery_rollback_uncorroborated_total") >>
                    recordRecoveryPhase("rollback_uncorroborated", direction) >>
                    RollbackTargetNotCorroborated(metadata.ordinal, tips.size).raiseError[F, Unit]
              }
            }
            // Clean up snapshots above the network tip (e.g. from a minority fork).
            _ <- snapshotStorage.cleanupAbove(metadata.ordinal)
            _ <- combinedSnapshotCheckpointFileSystemStorage.deleteAbove(metadata.ordinal)
            // Clear in-memory snapshot caches. During a network partition the node may have
            // produced minority-fork snapshots whose hashes differ from the canonical chain.
            // If we keep stale cache entries, the download replay will fail when it tries to
            // chain canonical ordinal N+1 onto a forked ordinal N (hash mismatch in set()).
            _ <- lastNGlobalSnapshotStorage.clear
            _ <- lastGlobalSnapshotStorage.clear
            // Reset consensus manager state (observation key, last outcome) so the fresh
            // initFromDownload can set them cleanly.
            _ <- consensus.manager.resetForRecovery
            // Clear event mempool. Stale events from before recovery (especially
            // UpdateNodeParameters) change reward calculations and cause validation
            // failures when the follower includes events the leader doesn't have.
            _ <- eventMempool.clear
            _ <- logger.info("[RecoveryDownload] Cleared event mempool")
            // Fetch only the gap: the download() hash-chain walker already stops at persisted snapshots
            result <- download(metadata.hash, metadata.ordinal, none)
            _ <- logger.info(
              s"[RecoveryDownload] Gap fetched. Latest downloaded: ordinal=${result._1.ordinal.show}, target=${metadata.ordinal.show}, " +
                s"direction=${recoveryDirection(localOrdinal.getOrElse(result._1.ordinal), result._1.ordinal)}"
            )
          } yield result
        val guardedBody =
          Async[F].timeoutTo(
            body,
            downloadStartMaxDuration,
            DownloadStartTimedOut.raiseError[F, DownloadResult]
          )
        guardedBody
          .flatTap(_ => recordStartOutcome("recovery", DownloadOutcome.Success) >> recordDownloadPhase("recovery", "start_success"))
          .onError {
            case err =>
              val outcome = classifyStartError(err)
              val maybeLog =
                if (outcome.isUnclassified) logUnclassifiedStartError(err) else Async[F].unit
              maybeLog >> recordStartOutcome("recovery", outcome)
          }
      }

      def recoveryObserve(result: DownloadResult): F[(DownloadResult, ObservationLimit)] = {
        val (lastSnapshot, lastContext) = result
        val body = for {
          _ <- recordDownloadPhase("recovery", "observe_entered")
          // Random 1-5 rounds observation to stagger recovery re-entry and mitigate thundering herd.
          // Minimum of 1 ensures at least one round is observed before rejoining consensus.
          recoveryRounds <- Random[F].betweenLong(1L, 6L)
          recoveryOffset = NonNegLong.unsafeFrom(recoveryRounds)
          // Reset storage heads to the downloaded snapshot so the normal observe
          // path can do sequential updates from here.
          hashedSnapshot <- hasherSelector.withCurrent(implicit hs => lastSnapshot.toHashed)
          _ <- lastNGlobalSnapshotStorage.setForRecovery(hashedSnapshot, lastContext)
          _ <- lastGlobalSnapshotStorage.setForRecovery(hashedSnapshot, lastContext)
          _ <- hasherSelector.withCurrent { implicit hs =>
            globalSnapshotConsensusStorage.setHeadForRecovery(lastSnapshot, lastContext)
          }
          // Sync MptStore to match the downloaded snapshot's state. During network isolation,
          // the MPT may have accumulated stale mutations (from abandoned rounds that partially
          // mutated state before savepoint restore, or from ordinals computed against a
          // different chain). syncFullIfNeeded rebuilds the MPT from the snapshot's state
          // entries (already available as checkpoint data in the snapshot — no full re-download
          // needed). This ensures the next consensus round computes the correct state proof.
          _ <- hasherSelector.withCurrent { implicit hs =>
            lastContext.allStateEntries[F].flatMap(mptStore.syncFull[Json](_, lastSnapshot.ordinal))
          }
          // Stable-tip shortcut (mirror of observe()): if a strict-majority of Ready peers
          // shows the cluster is already at (or behind) our recovered tip with matching hash,
          // skip the forward-observe loop entirely. Without this, a cluster that is stalled at
          // tip N makes us loop forever asking for N+1 (which never exists), because the
          // hardcoded `lastSnapshot.ordinal + recoveryOffset` target is unreachable.
          // This is the tip-plus-one recovery loop, separate from B2.
          readyPeerTips <- getReadyPeerTips
          _ <- Metrics[F].updateGauge("dag_recovery_ready_peer_tips_size", readyPeerTips.size.toLong)
          recoveryObservationLimit = chooseObservationLimit(
            hashedSnapshot.ordinal,
            hashedSnapshot.hash,
            readyPeerTips,
            recoveryOffset
          )
          _ <- recordPeerTipAlignment(
            "recovery",
            hashedSnapshot.ordinal,
            hashedSnapshot.hash,
            readyPeerTips,
            recoveryObservationLimit
          )
          isShortcut = recoveryObservationLimit === hashedSnapshot.ordinal && readyPeerTips.size >= minReadyQuorum
          observeMode = if (isShortcut) "shortcut" else "forward_observe"
          _ <- Applicative[F].whenA(isShortcut)(
            logger.info(
              s"[RecoveryDownload] Caught-up shortcut: local=${hashedSnapshot.ordinal.show} " +
                s"hash=${hashedSnapshot.hash.value.take(8)}, majority of ${readyPeerTips.size} Ready peers " +
                s"at or behind; skipping forward observe"
            ) >> recordObserveOutcome("recovery", DownloadOutcome.Shortcut) >>
              recordRecoveryPhase("observe", "shortcut")
          )
          _ <- Applicative[F].unlessA(isShortcut)(
            logger.info(
              s"[RecoveryDownload] Storage and MPT reset to ordinal ${hashedSnapshot.ordinal.show}, " +
                s"observing to ${recoveryObservationLimit.show} (peers=${readyPeerTips.size}, offset=$recoveryOffset)"
            )
          )
          // Deadline: if observe doesn't complete within 5 minutes, the observe loop is stuck
          // (peers moved too far ahead for sequential fetch). Abandon and re-trigger recovery.
          // Shortcut path (lastSnapshot.ordinal === recoveryObservationLimit) returns immediately.
          observeResult <- Async[F].timeoutTo(
            observeWithLimit(result, recoveryObservationLimit),
            5.minutes,
            Async[F].raiseError(ObserveDeadlineExceeded(lastSnapshot.ordinal, recoveryObservationLimit))
          )
          (observedResult, observationLimit) = observeResult
          (observedSnapshot, observedContext) = observedResult
          // Sync consensus SnapshotStorage head to observed tip so prepend works on the next round
          _ <- hasherSelector.withCurrent { implicit hs =>
            globalSnapshotConsensusStorage.setHeadForRecovery(observedSnapshot, observedContext)
          }
          _ <- logger.info(
            s"[RecoveryDownload] Consensus head synced to ordinal ${observedSnapshot.ordinal.show}, observationLimit=${observationLimit.show}, mode=$observeMode"
          )
          _ <- Applicative[F].unlessA(isShortcut)(
            recordObserveOutcome("recovery", DownloadOutcome.ForwardObserveSuccess) >>
              recordRecoveryPhase("observe", "forward_observe_success")
          )
          _ <- recordDownloadPhase("recovery", "observe_success")
        } yield observeResult

        body.onError {
          case err =>
            val outcome = classifyObserveError(err)
            val maybeLog =
              if (outcome.isUnclassified) logUnclassifiedObserveError(err) else Async[F].unit
            maybeLog >> recordObserveOutcome("recovery", outcome) >>
              recordRecoveryPhase("observe", outcome.label)
        }
      }

      // Bounded convergence loop: a single pass of recoveryStart + observe
      // can finish while the cluster has already moved past the observed tip because the observe
      // step uses a random 1-5 round offset. A peer that rejoins while still materially behind
      // immediately becomes a leader candidate with a stale view, wedging the round it leads
      // and forcing additional stall-cycle → recovery bounces ("whack-a-mole").
      //
      // Iterate recoveryStart + rejoin + recoveryObserve until the observed tip is within
      // `recoveryTargetLagOrdinals` of the cluster's freshly-fetched tip. Bounded by
      // `recoveryMaxIterations` and `recoveryMaxWallClock`; on exhaustion we raise and let the
      // outer handler transition the node back to `WaitingForDownload` for a fresh attempt.
      //
      // K=2 because one additional ordinal of drift is normal between the observe exit and the
      // fresh metadata fetch. K=1 would produce spurious retries under healthy cluster activity.
      val recoveryTargetLagOrdinals: Long = 2L
      val recoveryMaxIterations: Int = 5
      val recoveryMaxWallClock: FiniteDuration = 10.minutes

      def convergingRecoveryCycle: F[(DownloadResult, ObservationLimit)] = {
        def attempt(
          iteration: Int,
          startMs: FiniteDuration
        ): F[(DownloadResult, ObservationLimit)] =
          for {
            _ <- logger.info(s"[RecoveryDownload] Convergence iteration ${iteration + 1}/$recoveryMaxIterations")
            // Iterations > 0: force state back to WaitingForDownload so `tryModifyState(WaitingForDownload, ...)`
            // below can succeed again. `recoveryObserve` advances state to `Observing` internally, and the
            // normal path would continue from there to `WaitingForReady`. When we decide to iterate instead
            // of accept recovery, we reset. setNodeState is the same forcing mechanism used in the outer
            // error handler at ~line 381 for recovery-failure retries.
            _ <- Async[F].whenA(iteration > 0)(nodeStorage.setNodeState(NodeState.WaitingForDownload))
            // Keep recoveryStart wrapped in its own state transition so the contract (state ends in
            // WaitingForObserving before recoveryObserve runs) is preserved on every iteration. The prior
            // attempt at wrapping the whole convergence loop broke this — `recoveryObserve`'s internal
            // `trySetObservationKey` expects `WaitingForObserving`, but the state sat at `DownloadInProgress`
            // for the duration of the cycle.
            initial <- nodeStorage.tryModifyState(
              NodeState.WaitingForDownload,
              NodeState.DownloadInProgress,
              NodeState.WaitingForObserving
            )(recoveryStart)
            _ <- joining.rejoinAfterRecovery.handleErrorWith { err =>
              logger.warn(err)("[RecoveryDownload] Cluster rejoin failed, continuing anyway")
            }
            observed <- recoveryObserve(initial)
            observedOrdinal = observed._1._1.ordinal
            freshTip <- getLatestMetadata
            lag = freshTip.ordinal.value.value - observedOrdinal.value.value
            nowMs <- Async[F].monotonic
            elapsed = nowMs - startMs
            result <-
              if (lag <= recoveryTargetLagOrdinals)
                logger
                  .info(
                    s"[RecoveryDownload] Converged at ordinal ${observedOrdinal.show} (cluster tip ${freshTip.ordinal.show}, " +
                      s"lag=$lag, iterations=${iteration + 1}, elapsed=${elapsed.toSeconds}s)"
                  ) >> recordConvergenceOutcome("converged").as(observed)
              else if (iteration + 1 >= recoveryMaxIterations || elapsed >= recoveryMaxWallClock) {
                val outcome =
                  if (elapsed >= recoveryMaxWallClock) "max_wallclock" else "max_iterations"
                logger.warn(
                  s"[RecoveryDownload] Budget exhausted: observed=${observedOrdinal.show} clusterTip=${freshTip.ordinal.show} " +
                    s"lag=$lag iterations=${iteration + 1} elapsed=${elapsed.toSeconds}s — failing recovery to trigger retry"
                ) >>
                  recordConvergenceOutcome(outcome) >>
                  RecoveryConvergenceFailed(
                    observedOrdinal,
                    freshTip.ordinal,
                    lag,
                    iteration + 1,
                    elapsed
                  ).raiseError[F, (DownloadResult, ObservationLimit)]
              } else
                logger.info(
                  s"[RecoveryDownload] Observed=${observedOrdinal.show}, cluster tip ${freshTip.ordinal.show}, " +
                    s"lag=$lag > $recoveryTargetLagOrdinals — iterating to catch up further"
                ) >> attempt(iteration + 1, startMs)
          } yield result
        Async[F].monotonic.flatMap(start => attempt(0, start))
      }

      convergingRecoveryCycle.flatMap { result =>
        val ((snapshot, context), observationLimit) = result
        consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context, isRecovery = true) >>
          recordDownloadPhase("recovery", "facilitate_enqueued")
      }
        .onError(logger.error(_)("[RecoveryDownload] Unexpected failure, will retry"))
        .handleErrorWith { err =>
          // Recovery failed — transition back to WaitingForDownload so DownloadDaemon retries.
          // Reraise so DownloadDaemon's error handler fires (which does NOT clear the recovery flag)
          // instead of the success path (which clears it via clearRecoveryDownload).
          //
          // Jittered backoff: if multiple nodes in the cluster simultaneously failed recovery
          // (e.g. post-cluster-restart or transient network flakiness), retrying without delay
          // makes them all hammer the same peer in lockstep. A 1–5s random sleep spreads the
          // retries enough that the serving peer can respond to them sequentially.
          Random[F].betweenLong(1000L, 5001L).flatMap { jitterMs =>
            logger.warn(s"[RecoveryDownload] Failed, retrying in ${jitterMs}ms (jittered)") >>
              Async[F].sleep(jitterMs.millis) >>
              nodeStorage.getNodeState.flatMap {
                case state if state =!= NodeState.WaitingForDownload && state =!= NodeState.Ready =>
                  nodeStorage.setNodeState(NodeState.WaitingForDownload)
                case _ => Async[F].unit
              } >> err.raiseError[F, Unit]
          }
        }
    }

    def start(implicit hasherSelector: HasherSelector[F]): F[DownloadResult] = {

      def getLatestMetadata: F[SnapshotMetadata] = {
        val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))

        retryingOnAllErrors[SnapshotMetadata](
          policy = retryPolicy,
          onError = (err: Throwable, retryDetails: RetryDetails) =>
            logger.error(err)(s"Error when trying to fetch latest metadata (attempt=${retryDetails.retriesSoFar}), selecting new peer")
        ) {
          peerSelect.select.flatMap(p2pClient.globalSnapshot.getLatestMetadata.run(_))
        }
      }

      def performInitialCleanup(metadata: SnapshotMetadata, result: Option[DownloadResult]): F[Unit] =
        Async[F].whenA(result.isEmpty)(
          logger.info(s"[Download] Cleanup for snapshots greater than ${metadata.ordinal}") >>
            snapshotStorage.cleanupAbove(metadata.ordinal) >>
            combinedSnapshotCheckpointFileSystemStorage.deleteAbove(metadata.ordinal) >>
            mptStore.deleteAbove(metadata.ordinal) >>
            lastNGlobalSnapshotStorage.clear >>
            lastGlobalSnapshotStorage.clear >>
            consensus.manager.resetForRecovery >>
            eventMempool.clear >>
            logger.info("[Download] Cleared event mempool for recovery")
        )

      def logDownloadInfo(startingPoint: SnapshotOrdinal, metadata: SnapshotMetadata): F[Unit] =
        logger.info(s"Download for startingPoint=$startingPoint. Latest metadata=${metadata.show}")

      def calculateBatchSize(metadata: SnapshotMetadata, startingPoint: SnapshotOrdinal): Long =
        metadata.ordinal.value.value - startingPoint.value.value

      def shouldStopDownloading(batchSize: Long, startingPoint: SnapshotOrdinal): Boolean =
        batchSize <= minBatchSizeToStartObserving && startingPoint =!= lastFullGlobalSnapshotOrdinal

      def downloadLoop(
        startingPoint: SnapshotOrdinal,
        result: Option[DownloadResult]
      ): F[DownloadResult] =
        for {
          metadata <- getLatestMetadata
          _ <- performInitialCleanup(metadata, result)
          _ <- logDownloadInfo(startingPoint, metadata)

          batchSize = calculateBatchSize(metadata, startingPoint)

          finalResult <-
            if (shouldStopDownloading(batchSize, startingPoint)) {
              result
                .map(_.pure[F])
                .getOrElse(UnexpectedState.raiseError[F, DownloadResult])
            } else {
              for {
                (snapshot, context) <- download(metadata.hash, metadata.ordinal, result)
                nextResult <- downloadLoop(snapshot.ordinal, (snapshot, context).some)
              } yield nextResult
            }
        } yield finalResult

      downloadLoop(lastFullGlobalSnapshotOrdinal, none[DownloadResult])
    }

    def observeWithLimit(result: DownloadResult, initialObservationLimit: ObservationLimit)(
      implicit hasherSelector: HasherSelector[F]
    ): F[(DownloadResult, ObservationLimit)] = {
      // Re-evaluate the observation limit against a fresh peer-tip view. Used when fetchNextSnapshot's
      // bounded retry cap exhausts: if the cluster has reached a stable tip at our local ordinal
      // (shortcut condition fires), exit observe successfully. Otherwise return the new (possibly
      // unchanged) limit and the next go() invocation will try fetchNextSnapshot with another bounded
      // retry. This is the tip+1 recovery loop fix: prevents an indefinite 10s loop asking for an
      // ordinal that will never finalize because the cluster is at our tip but lastSnapshot.ordinal+1
      // is the round being worked on rather than a finalized snapshot.
      def reEvaluateLimit(lastSnapshot: Signed[GlobalIncrementalSnapshot]): F[(ObservationLimit, Boolean)] =
        for {
          hashed <- hasherSelector.withCurrent(implicit h => lastSnapshot.toHashed)
          readyPeerTips <- getReadyPeerTips
          newLimit = chooseObservationLimit(hashed.ordinal, hashed.hash, readyPeerTips, observationOffset)
          isShortcut = newLimit === hashed.ordinal && readyPeerTips.size >= minReadyQuorum
        } yield (newLimit, isShortcut)

      def go(result: DownloadResult, currentLimit: ObservationLimit): F[(DownloadResult, ObservationLimit)] = {
        val (lastSnapshot, lastState) = result

        for {
          _ <- updateStoragesWithDownloadedSnapshot(lastSnapshot, lastState)
          out <-
            if (lastSnapshot.ordinal === currentLimit)
              (result, currentLimit).pure[F]
            else
              fetchNextSnapshot(result)
                .flatMap(nextResult => go(nextResult, currentLimit))
                .recoverWith {
                  case err @ (CannotFetchSnapshot | InvalidChain) =>
                    reEvaluateLimit(lastSnapshot).flatMap {
                      case (newLimit, true) =>
                        logger.info(
                          s"[observeWithLimit] Mid-loop stable-tip shortcut: local=${lastSnapshot.ordinal.show}, " +
                            s"prior limit=${currentLimit.show}, re-evaluated limit=${newLimit.show}, " +
                            s"majority of Ready peers at or behind; exiting observe (err=${err.getClass.getSimpleName})"
                        ) >> (result, newLimit).pure[F]
                      case (newLimit, false) =>
                        logger.info(
                          s"[observeWithLimit] Retries exhausted at ordinal ${lastSnapshot.ordinal.show}, " +
                            s"re-evaluated limit ${currentLimit.show} -> ${newLimit.show}; continuing " +
                            s"(err=${err.getClass.getSimpleName})"
                        ) >> go(result, newLimit)
                    }
                }
        } yield out
      }

      consensus.manager.registerForConsensus(initialObservationLimit) >>
        go(result, initialObservationLimit)
    }

    // Per-peer timeout for metadata probes. A single slow or half-partitioned Ready peer cannot
    // block the shortcut decision indefinitely; one that errors or times out simply doesn't vote.
    private val perPeerTipTimeout: FiniteDuration = 3.seconds

    /** Query every responsive Ready or WaitingForReady peer's `/global-snapshots/latest/metadata` in parallel. Used by both the normal
      * observe path and the recovery observe path to decide whether the cluster is already at our tip (shortcut) or ahead (fetch forward).
      *
      * Pool widened beyond Ready to include WaitingForReady (matches the alpha.63/64 widening of PeerSelect, SelectablePeerDiscoveryDelay,
      * StateTransitions.selectPeer, and SnapshotRoutes.validStateForSnapshotReturn). On a stalled rollback-lead topology the only Ready
      * peer may be the rollback-lead itself while sibling source nodes sit in WaitingForReady; with just one Ready respondent a single
      * timed-out probe drops `readyPeerTips.size` below `minReadyQuorum`, forcing recovering peers into the forward-observe path against an
      * ordinal the cluster cannot produce. Including WaitingForReady peers makes the shortcut decision robust under stall. SnapshotRoutes
      * already serves `/global-snapshots/latest/metadata` from WaitingForReady via the LastN fallback added in alpha.64.
      */
    private def getReadyPeerTips: F[List[PeerTip]] =
      clusterStorage.getResponsivePeers
        .map(_.filter(p => p.state === NodeState.Ready || p.state === NodeState.WaitingForReady))
        .map(_.toList)
        .flatMap(
          _.parTraverse(peer =>
            Async[F]
              .timeout(p2pClient.globalSnapshot.getLatestMetadata.run(peer), perPeerTipTimeout)
              .map(m => PeerTip(m.ordinal, m.hash).some)
              .handleErrorWith(err =>
                logger
                  .warn(err)(s"[Download] Unable to fetch latest metadata from peer ${peer.show}")
                  .as(none[PeerTip])
              )
          )
        )
        .map(_.flatten)

    def observe(result: DownloadResult)(implicit hasherSelector: HasherSelector[F]): F[(DownloadResult, ObservationLimit)] = {
      val (lastSnapshot, _) = result
      for {
        hashed <- hasherSelector.withCurrent(implicit h => lastSnapshot.toHashed)
        readyPeerTips <- getReadyPeerTips
        observationLimit = chooseObservationLimit(hashed.ordinal, hashed.hash, readyPeerTips, observationOffset)
        _ <- recordPeerTipAlignment("full", hashed.ordinal, hashed.hash, readyPeerTips, observationLimit)
        isShortcut = observationLimit === hashed.ordinal && readyPeerTips.size >= minReadyQuorum
        _ <- Applicative[F].whenA(isShortcut)(
          logger.warn(
            s"[Download] Caught-up shortcut: local=${hashed.ordinal.show} hash=${hashed.hash.value.take(8)}, " +
              s"majority of ${readyPeerTips.size} Ready peers at or behind; skipping next-snapshot observe"
          )
        )
        out <- observeWithLimit(result, observationLimit)
      } yield out
    }

    // Bounded retry cap for fetchNextSnapshot. With fetchSnapshotDelayBetweenTrials=10s this caps
    // a single fetch loop at ~60s before raising. Previously this was unbounded, which caused the
    // tip+1 recovery loop bug: if recoveryObserve fixed observationLimit to lastSnapshot.ordinal+1
    // while the cluster never finalized that ordinal (e.g. the cluster tip equals our local tip
    // and there is no next snapshot to fetch), retryingOnSomeErrors would loop forever on
    // CannotFetchSnapshot 404s and the outer observe loop never got a chance to re-evaluate the
    // limit against fresh peer tips. The cap lets the outer observeWithLimit.go.recoverWith
    // re-query getReadyPeerTips and call chooseObservationLimit again.
    val fetchNextRetryCap: Int = 6

    def fetchNextSnapshot(result: DownloadResult)(implicit hasherSelector: HasherSelector[F]): F[DownloadResult] = {
      def retryPolicy = limitRetries[F](fetchNextRetryCap).join(constantDelay(fetchSnapshotDelayBetweenTrials))

      def isWorthRetrying(err: Throwable): F[Boolean] = err match {
        case CannotFetchSnapshot | InvalidChain => true.pure[F]
        case _                                  => false.pure[F]
      }

      retryingOnSomeErrors(retryPolicy, isWorthRetrying, retry.noop[F, Throwable]) {
        val (lastSnapshot, lastContext) = result
        hasherSelector.withCurrent(implicit hs => fetchSnapshot(none, lastSnapshot.ordinal.next)).flatMap { snapshot =>
          hasherSelector.withCurrent { implicit hasher =>
            lastSnapshot.toHashed[F]
          }.flatMap { hashed =>
            Applicative[F].unlessA {
              Validator.isNextSnapshot(hashed, snapshot.value)
            }(InvalidChain.raiseError[F, Unit])
          } >>
            HasherSelector[F].withCurrent { implicit hasher =>
              globalSnapshotContextFns
                .createContext(
                  lastContext,
                  lastSnapshot,
                  snapshot,
                  fetchSnapshotByOrdinal
                )
            }
              .handleErrorWith(_ => InvalidChain.raiseError[F, GlobalSnapshotContext])
              .flatTap { _ =>
                snapshotStorage.writePersisted(snapshot)
              }
              .map((snapshot, _))

        }
      }
    }

    def download(hash: Hash, ordinal: SnapshotOrdinal, state: Option[DownloadResult])(
      implicit hasherSelector: HasherSelector[F]
    ): F[DownloadResult] = {

      def go(
        tmpMap: Map[SnapshotOrdinal, Hash],
        stepHash: Hash,
        stepOrdinal: SnapshotOrdinal
      ): F[DownloadResult] =
        isSnapshotPersistedOrReachedGenesis(stepHash, stepOrdinal).ifM(
          snapshotStorage.getHighestSnapshotInfoOrdinal(lte = stepOrdinal).flatMap {
            validateChain(tmpMap, _, ordinal, state)
          },
          snapshotStorage
            .readTmp(stepOrdinal)
            .flatMap {
              case Some(snapshot) =>
                hasherSelector.withCurrent(implicit hasher => snapshot.toHashed[F]).map { hashed =>
                  if (hashed.hash === stepHash) hashed.some else none[Hashed[GlobalIncrementalSnapshot]]
                }
              case None => none[Hashed[GlobalIncrementalSnapshot]].pure[F]
            }
            .flatMap {
              _.map(_.pure[F])
                .getOrElse(hasherSelector.withCurrent(implicit hs => fetchSnapshot(stepHash.some, stepOrdinal)).flatMap { snapshot =>
                  hasherSelector.withCurrent { implicit hasher =>
                    snapshotStorage.writeTmp(snapshot).flatMap(_ => snapshot.toHashed[F])
                  }
                })
                .flatMap { hashed =>
                  def updated = tmpMap + (hashed.ordinal -> hashed.hash)

                  PartialPrevious[SnapshotOrdinal]
                    .partialPrevious(hashed.ordinal)
                    .map {
                      go(updated, hashed.lastSnapshotHash, _)
                    }
                    .getOrElse(HashAndOrdinalMismatch.raiseError[F, DownloadResult])
                }
            }
        )

      go(Map.empty, hash, ordinal)
    }

    def isSnapshotPersistedOrReachedGenesis(hash: Hash, ordinal: SnapshotOrdinal): F[Boolean] = {
      def isSnapshotPersisted = snapshotStorage.isPersisted(hash)

      def didReachGenesis = ordinal === lastFullGlobalSnapshotOrdinal

      if (!didReachGenesis) {
        isSnapshotPersisted
      } else true.pure[F]
    }

    def validateChain(
      tmpMap: Map[SnapshotOrdinal, Hash],
      startingOrdinal: Option[SnapshotOrdinal],
      endingOrdinal: SnapshotOrdinal,
      state: Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
    )(implicit hasherSelector: HasherSelector[F]): F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] = {

      type Agg = DownloadResult

      def go(lastSnapshot: Signed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Agg] = {
        val nextOrdinal = lastSnapshot.ordinal.next

        def readSnapshot: F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpMap
          .get(nextOrdinal)
          .as(snapshotStorage.readTmp(nextOrdinal))
          .getOrElse(snapshotStorage.readPersisted(nextOrdinal))

        def persistLastSnapshot: F[Unit] =
          Applicative[F].whenA(tmpMap.contains(lastSnapshot.ordinal)) {
            snapshotStorage.readPersisted(lastSnapshot.ordinal).flatMap {
              _.map(snapshot =>
                hasherSelector
                  .withCurrent(implicit hasher => snapshot.toHashed[F])
                  .map(_.hash)
                  .flatMap(snapshotStorage.movePersistedToTmp(_, lastSnapshot.ordinal))
              ).getOrElse(Applicative[F].unit)
            } >>
              snapshotStorage
                .moveTmpToPersisted(lastSnapshot)
          }

        def processNextOrFinish: F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
          if (lastSnapshot.ordinal.value >= endingOrdinal.value) {
            (lastSnapshot, context).pure[F]
          } else
            readSnapshot.flatMap {
              case Some(snapshot) =>
                HasherSelector[F].withCurrent { implicit hasher =>
                  globalSnapshotContextFns
                    .createContext(
                      context,
                      lastSnapshot,
                      snapshot,
                      fetchSnapshotByOrdinal
                    )
                }
                  .flatTap(newContext =>
                    hasherSelector.withCurrent { implicit hasher =>
                      snapshotStorage
                        .hasCorrectSnapshotInfo(snapshot.ordinal, snapshot.stateProof)
                        .ifM(
                          ().pure[F],
                          snapshot.toHashed.flatMap { hashed =>
                            validator.validate(hashed, newContext).map(_.isValid)
                          }.ifM(
                            snapshotStorage.persistSnapshotInfoWithCutoff(snapshot.ordinal, newContext),
                            InvalidStateProof(snapshot.ordinal).raiseError[F, Unit]
                          )
                        )
                    }
                  )
                  .flatMap { state =>
                    updateStoragesWithDownloadedSnapshot(snapshot, state) >>
                      go(snapshot, state)
                  }
              case None => InvalidChain.raiseError[F, Agg]
            }

        // Use syncFullIfNeeded for atomic initialization - avoids race condition where
        // two concurrent calls both see mptEntries.isEmpty=true and both try to sync
        def performInitialSync: F[Unit] =
          logger.debug("Performing initial sync of MPT (if needed)") >>
            mptStore.syncFullIfNeeded[Json](
              hasherSelector.withCurrent(implicit h => context.allStateEntries[F]),
              lastSnapshot.ordinal
            )

        for {
          _ <- performInitialSync
          _ <- persistLastSnapshot
          result <- processNextOrFinish
        } yield result
      }

      // Search successively-lower persisted (snapshot, info) pairs until one validates. When
      // readCombined self-heals (deletes a mismatched pair and returns None), we MUST NOT fall
      // back to genesis: the chain has millions of blocks and re-downloading from genesis is
      // operationally unacceptable. Instead, ask getHighestSnapshotInfoOrdinal for the next-lower
      // persisted info ordinal and try again. The deleted info file is already gone, so the next
      // call naturally returns a strictly-lower ordinal. Bounded by maxPersistedSearchAttempts.

      def findHighestValidPersisted(lte: SnapshotOrdinal, attempts: Int)(
        implicit hasher: Hasher[F]
      ): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        if (attempts <= 0) {
          logger.warn(
            s"[validateChain] exhausted $maxPersistedSearchAttempts attempts searching for a valid persisted (snapshot, info) " +
              s"pair at or below ord=${lte.show}; will raise rather than re-download from genesis"
          ) >> none[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)].pure[F]
        } else
          snapshotStorage.getHighestSnapshotInfoOrdinal(lte = lte).flatMap {
            case None => none[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)].pure[F]
            case Some(ord) =>
              snapshotStorage.readCombined(ord).flatMap {
                case Some(result) => result.some.pure[F]
                case None         =>
                  // readCombined self-healed by deleting the bad pair at `ord`. The next call to
                  // getHighestSnapshotInfoOrdinal will return a strictly-lower ord, but we also
                  // pass `ord` explicitly (rather than `ord - 1`) to keep the bound aligned with
                  // any new write races. SnapshotOrdinal.MinValue terminates if we reach genesis.
                  findHighestValidPersisted(ord, attempts - 1)
              }
          }

      state
        .map(_.pure[F])
        .getOrElse {
          startingOrdinal match {
            case None =>
              // No persisted info anywhere on disk -- this is a genuine fresh-node bootstrap and
              // a genesis re-download is the only correct option. NOT the wedge-recovery path.
              getGenesisSnapshot(tmpMap)
            case Some(initial) =>
              hasherSelector
                .withCurrent(implicit hasher => findHighestValidPersisted(initial, maxPersistedSearchAttempts))
                .flatMap {
                  case Some(result) => result.pure[F]
                  case None         =>
                    // We had persisted state but every inspected pair failed validation and was
                    // discarded. Refuse to silently re-download from genesis (potentially millions
                    // of blocks). Raise so the FSM reverts state, the daemon retries, and an
                    // operator can intervene if the corruption persists across retries.
                    new RuntimeException(
                      s"All inspected persisted snapshot/info pairs at or below ord=${initial.show} " +
                        s"failed validation; refusing to re-download from genesis"
                    ).raiseError[F, (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
                }
          }
        }
        .flatMap {
          case (s, c) =>
            updateStoragesWithDownloadedSnapshot(s, c) >>
              go(s, c)
        }
    }

    def getGenesisSnapshot(
      tmpMap: Map[SnapshotOrdinal, Hash]
    )(implicit hasherSelector: HasherSelector[F]): F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
      snapshotStorage
        .readGenesis(lastFullGlobalSnapshotOrdinal)
        .flatMap {
          _.map(_.pure[F]).getOrElse {
            hasherSelector.withCurrent { implicit hasher =>
              fetchGenesis(lastFullGlobalSnapshotOrdinal)
                .flatTap(snapshotStorage.writeGenesis)
            }
          }
        }
        .flatMap { genesis =>
          val incrementalGenesisOrdinal = genesis.ordinal.next

          tmpMap
            .get(incrementalGenesisOrdinal)
            .as(snapshotStorage.readTmp(incrementalGenesisOrdinal))
            .getOrElse(snapshotStorage.readPersisted(incrementalGenesisOrdinal))
            .flatMap {
              case Some(snapshot) => (genesis.value, snapshot).pure[F]
              case None           => FirstIncrementalNotFound.raiseError[F, (GlobalSnapshot, Signed[GlobalIncrementalSnapshot])]
            }
            .map { case (full, incremental) => (incremental, full.info.toGlobalSnapshotInfo) }
        }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[GlobalIncrementalSnapshot]] =
      clusterStorage.getResponsivePeers
        .map(NodeState.ready)
        .flatTap(peers => ExitOnFork.exitOnCheck("CL_EXIT_ON_FOLLOWER_DOWNLOAD", () => peers.map(_.id)))
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.debug(s"Downloading snapshot hash=${hash.show}, ordinal=${ordinal.show}")
        }
        .flatMap { peers =>
          type Success = Signed[GlobalIncrementalSnapshot]
          type Result = Option[Success]
          type Agg = (List[Peer], Result)

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.globalSnapshot
                .get(ordinal)
                .run(peer)
                .flatMap(snapshot => snapshot.toHashed[F])
                .map(_.some)
                .handleErrorWith(e =>
                  logger
                    .warn(e)(s"Unable to retrieve snapshot at ordinal ${ordinal.show} from peer ${peer.show}")
                    .as(none[Hashed[GlobalIncrementalSnapshot]])
                )
                .map {
                  case Some(snapshot) if hash.forall(_ === snapshot.hash) => snapshot.signed.some.asRight[Agg]
                  case _                                                  => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchSnapshot.raiseError[F, Signed[GlobalIncrementalSnapshot]]
        }

    def fetchGenesis(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[GlobalSnapshot]] =
      clusterStorage.getResponsivePeers
        .map(NodeState.ready)
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.info(s"Downloading genesis snapshot ordinal=${ordinal}")
        }
        .flatMap { peers =>
          type Success = Signed[GlobalSnapshot]
          type Agg = (List[Peer], Option[Signed[GlobalSnapshot]])
          type Result = Option[Success]

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.globalSnapshot
                .getFull(ordinal)
                .run(peer)
                .flatMap(_.toHashed[F])
                .map(_.some)
                .handleError(_ => none[Hashed[GlobalSnapshot]])
                .map {
                  case Some(snapshot) => snapshot.signed.some.asRight[Agg]
                  case _              => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchGenesisSnapshot.raiseError[F, Signed[GlobalSnapshot]]
        }
  }

  case object HashAndOrdinalMismatch extends NoStackTrace

  case object CannotFetchSnapshot extends NoStackTrace

  case object CannotFetchGenesisSnapshot extends NoStackTrace with RecoveryFallbackEligible

  case object FirstIncrementalNotFound extends NoStackTrace

  case object InvalidChain extends NoStackTrace with RecoveryFallbackEligible

  case class InvalidStateProof(ordinal: SnapshotOrdinal) extends NoStackTrace

  case object UnexpectedState extends NoStackTrace

  // Raised when Download.start exceeds the outer watchdog budget. Converts a silent hang inside
  // start (whether in fetchSnapshot, validateChain, createContext, mptStore.syncFullIfNeeded, or
  // any other operation that could block without raising) into an explicit Throwable that:
  //   1. Triggers the .onError instrumentation in `download` so the metric records `start_timed_out`
  //   2. Propagates to NodeStorage.tryModifyState which reverts DLI -> WFD
  //   3. Allows DownloadDaemon to schedule a fresh start() attempt
  // Without this watchdog, a hung start() leaves the node permanently in DownloadInProgress with
  // no metric increment and no FSM revert -- the observed silent-peer mode in alpha.70.
  case object DownloadStartTimedOut extends NoStackTrace
}
