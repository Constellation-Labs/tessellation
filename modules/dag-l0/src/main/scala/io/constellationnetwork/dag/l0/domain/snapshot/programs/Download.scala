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
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.snapshot.{PeerSelect, Validator}
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.CombinedSnapshotCheckpointFileSystemStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
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

object Download {

  /** Per-Ready-peer advertised tip. */
  private[snapshot] final case class PeerTip(ordinal: SnapshotOrdinal, hash: Hash)

  /** Minimum number of Ready peer responses required before trusting a caught-up shortcut. Two responding peers is the floor — a single
    * responder can be a lying / stale adversary, two independent responses that must ALSO agree make the check resistant to a single
    * misbehaving or partitioned node. Matches the "present.size >= 2" floor used in ForkRecoveryDetector for similar cross-peer reasoning.
    */
  private[snapshot] val minReadyQuorum: Int = 2

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
    * Data source for peer tips: `SnapshotRoutes:/global-snapshots/latest/metadata`, which is `whenNodeReady`-gated on the responder — a
    * response is evidence the responder is actually in `NodeState.Ready`.
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
    joining: Joining[F]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): Download[F, GlobalIncrementalSnapshot] = new Download[F, GlobalIncrementalSnapshot] {

    val logger = Slf4jLogger.getLogger[F]

    private val validator = StateProofValidator.forGlobal(Some(mptStore.underlying))

    val minBatchSizeToStartObserving: Long = 1L
    val observationOffset = NonNegLong(1L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

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

    def download(implicit hasherSelector: HasherSelector[F]): F[Unit] =
      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(start)
        .flatMap(observe)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result
          for {
            _ <- consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context)
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
    def recoveryDownload(implicit hasherSelector: HasherSelector[F]): F[Unit] = {
      def getLatestMetadata: F[SnapshotMetadata] = {
        val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
        retryingOnAllErrors[SnapshotMetadata](
          policy = retryPolicy,
          onError = (err: Throwable, details: RetryDetails) =>
            logger.error(err)(s"[RecoveryDownload] Error fetching metadata (attempt=${details.retriesSoFar})")
        ) {
          peerSelect.select.flatMap(p2pClient.globalSnapshot.getLatestMetadata.run(_))
        }
      }

      def recoveryStart: F[DownloadResult] =
        for {
          metadata <- getLatestMetadata
          _ <- logger.info(
            s"[RecoveryDownload] Starting incremental recovery. Network tip: ordinal=${metadata.ordinal.show}, hash=${metadata.hash.show}"
          )
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
            s"[RecoveryDownload] Gap fetched. Latest downloaded: ordinal=${result._1.ordinal.show}"
          )
        } yield result

      def recoveryObserve(result: DownloadResult): F[(DownloadResult, ObservationLimit)] = {
        val (lastSnapshot, lastContext) = result
        for {
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
          _ <- logger.info(
            s"[RecoveryDownload] Storage and MPT reset to ordinal ${lastSnapshot.ordinal.show}, entering observe for $recoveryOffset rounds (random 1-5)"
          )
          // Reuse the normal observe path — after setForRecovery, snapshots are sequential.
          // observe updates lastN and lastGlobal storages but NOT the consensus SnapshotStorage head.
          // Override observationOffset for this call by computing the limit ourselves.
          // Deadline: if observe doesn't complete within 5 minutes, the observe loop is stuck
          // (peers moved too far ahead for sequential fetch). Abandon and re-trigger recovery.
          observeResult <- {
            val recoveryObservationLimit = SnapshotOrdinal(lastSnapshot.ordinal.value |+| recoveryOffset)
            Async[F].timeoutTo(
              observeWithLimit(result, recoveryObservationLimit),
              5.minutes,
              Async[F].raiseError(ObserveDeadlineExceeded(lastSnapshot.ordinal, recoveryObservationLimit))
            )
          }
          (observedResult, observationLimit) = observeResult
          (observedSnapshot, observedContext) = observedResult
          // Sync consensus SnapshotStorage head to observed tip so prepend works on the next round
          _ <- hasherSelector.withCurrent { implicit hs =>
            globalSnapshotConsensusStorage.setHeadForRecovery(observedSnapshot, observedContext)
          }
          _ <- logger.info(
            s"[RecoveryDownload] Consensus head synced to ordinal ${observedSnapshot.ordinal.show}"
          )
        } yield observeResult
      }

      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(recoveryStart)
        .flatTap { _ =>
          // Re-announce to cluster peers BEFORE the observe phase. During recovery, other
          // nodes may have removed this node from their peer lists (LocalHealthcheck eviction).
          // Without re-announcing first, the observe phase can't receive snapshot gossip from
          // peers, causing it to timeout after 5 minutes and re-trigger recovery indefinitely.
          joining.rejoinAfterRecovery.handleErrorWith { err =>
            logger.warn(err)("[RecoveryDownload] Cluster rejoin failed, continuing anyway")
          }
        }
        .flatMap(recoveryObserve)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result
          consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context, isRecovery = true)
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

    def observeWithLimit(result: DownloadResult, observationLimit: ObservationLimit)(
      implicit hasherSelector: HasherSelector[F]
    ): F[(DownloadResult, ObservationLimit)] = {
      def go(result: DownloadResult): F[DownloadResult] = {
        val (lastSnapshot, lastState) = result

        for {
          _ <- updateStoragesWithDownloadedSnapshot(lastSnapshot, lastState)
          result <-
            if (lastSnapshot.ordinal === observationLimit) {
              result.pure[F]
            } else fetchNextSnapshot(result) >>= go
        } yield result
      }

      consensus.manager.registerForConsensus(observationLimit) >>
        go(result).map((_, observationLimit))
    }

    def observe(result: DownloadResult)(implicit hasherSelector: HasherSelector[F]): F[(DownloadResult, ObservationLimit)] = {
      val (lastSnapshot, _) = result
      val perPeerTimeout: FiniteDuration = 3.seconds

      // Query every responsive Ready peer's `/global-snapshots/latest/metadata` in parallel.
      // Per-peer timeout caps the total observe() latency — a single slow or half-partitioned
      // Ready peer cannot block the shortcut decision indefinitely. A peer that errors or
      // times out simply does not vote; the quorum check still requires `minReadyQuorum`
      // agreeing responders so sparse responses fall through to the safe default.
      def getReadyPeerTips: F[List[PeerTip]] =
        clusterStorage.getResponsivePeers
          .map(NodeState.ready)
          .map(_.toList)
          .flatMap(
            _.parTraverse(peer =>
              Async[F]
                .timeout(p2pClient.globalSnapshot.getLatestMetadata.run(peer), perPeerTimeout)
                .map(m => PeerTip(m.ordinal, m.hash).some)
                .handleErrorWith(err =>
                  logger
                    .warn(err)(s"[Download] Unable to fetch latest metadata from ready peer ${peer.show}")
                    .as(none[PeerTip])
                )
            )
          )
          .map(_.flatten)

      for {
        hashed <- hasherSelector.withCurrent(implicit h => lastSnapshot.toHashed)
        readyPeerTips <- getReadyPeerTips
        observationLimit = chooseObservationLimit(hashed.ordinal, hashed.hash, readyPeerTips, observationOffset)
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

    def fetchNextSnapshot(result: DownloadResult)(implicit hasherSelector: HasherSelector[F]): F[DownloadResult] = {
      def retryPolicy = constantDelay(fetchSnapshotDelayBetweenTrials)

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
          logger.info("Performing initial sync of MPT (if needed)") >>
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

      state
        .map(_.pure[F])
        .getOrElse {
          startingOrdinal
            .flatTraverse(ordinal => hasherSelector.withCurrent(implicit hasher => snapshotStorage.readCombined(ordinal)))
            .flatMap {
              _.map(_.pure[F]).getOrElse(
                getGenesisSnapshot(tmpMap)
              )
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
          logger.info(s"Downloading snapshot hash=${hash.show}, ordinal=${ordinal.show}")
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

  case object CannotFetchGenesisSnapshot extends NoStackTrace

  case object FirstIncrementalNotFound extends NoStackTrace

  case object InvalidChain extends NoStackTrace

  case class InvalidStateProof(ordinal: SnapshotOrdinal) extends NoStackTrace

  case object UnexpectedState extends NoStackTrace
}
