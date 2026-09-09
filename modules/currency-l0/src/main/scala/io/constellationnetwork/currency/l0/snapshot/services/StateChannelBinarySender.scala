package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.std.Supervisor
import cats.effect.syntax.all._
import cats.effect.{Async, Ref, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.metrics.{updateBackpressuredStateChannelBinaryMetrics, updateStateChannelRetryParametersMetrics}
import io.constellationnetwork.currency.l0.snapshot.storage.{RecoverySyncPublicationStorage, StateChannelBinaryOutboxStorage}
import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait StateChannelBinarySender[F[_]] {
  def enqueue(
    binary: Hashed[StateChannelSnapshotBinary],
    currencySnapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Unit]

  def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit]

  def clearPending: F[Unit]

  /** Closes publication before local history is replaced by download or rollback. */
  def disablePublishing: F[Unit]

  /** Opens publication after download/rollback has installed and reconciled canonical Currency state.
    */
  def enablePublishing: F[Unit]
}

object StateChannelBinarySender {
  def make[F[_]: Async: Hasher: Metrics](
    identifierStorage: IdentifierStorage[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    selfId: PeerId,
    environment: AppEnvironment,
    customPeersAllowanceList: Option[Set[AllowanceListEntry]],
    cluster: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    recoverySyncPublicationStorage: RecoverySyncPublicationStorage[F],
    stateChannelBinaryOutboxStorage: StateChannelBinaryOutboxStorage[F],
    maxTrackedBinaries: Int = 10000,
    initialPublishingEnabled: Boolean = true,
    onRecoveryPublicationConfirmed: F[Unit]
  )(implicit S: Supervisor[F]): F[StateChannelBinarySender[F]] = {
    val logger = Slf4jLogger.getLoggerFromName(this.getClass.getName)

    for {
      tracker <- BinaryTracker.make[F](maxTrackedBinaries)
      publicationEnabled <- Ref.of[F, Boolean](initialPublishingEnabled)
      poster = new BinaryPoster[F](
        identifierStorage,
        globalL0ClusterStorage,
        stateChannelSnapshotClient,
        stateChannelAllowanceLists,
        selfId,
        environment,
        customPeersAllowanceList,
        tracker
      )
      sender = new StateChannelBinarySenderImpl[F](
        tracker,
        poster,
        lastGlobalSnapshotStorage,
        identifierStorage,
        cluster,
        selfId,
        maxTrackedBinaries,
        fa => S.supervise(fa).void,
        logger,
        recoverySyncPublicationStorage.some,
        stateChannelBinaryOutboxStorage.some,
        publicationEnabled,
        nodeStorage.getNodeState.map(_ === NodeState.Ready),
        onRecoveryPublicationConfirmed,
        error =>
          nodeStorage
            .tryModifyStateGetResult(
              Set[NodeState](NodeState.Ready, NodeState.WaitingForReady, NodeState.Observing),
              NodeState.WaitingForDownload
            )
            .flatMap { transition =>
              val outcome = transition match {
                case NodeStateTransition.Success => "download_requested"
                case NodeStateTransition.Failure => "already_not_participating"
              }
              Metrics[F].incrementCounter(
                "dag_currency_l0_binary_outbox_canonical_mismatch_total",
                Seq(Metrics.unsafeLabelName("outcome") -> outcome)
              ) >> logger.error(error)(s"Canonical Currency binary mismatch; publication stopped, recovery=$outcome")
            }
      )
      _ <- sender.refillFromOutbox
      _ <- recoverySyncPublicationStorage.get.flatMap {
        case Some(publication) if publication.locallyCommitted && !publication.expired =>
          sender.enqueue(
            Hashed(publication.binary, publication.binaryHash, publication.proofsHash),
            publication.currencySnapshotOrdinal,
            none
          ) >>
            (Metrics[F].updateGauge(
              "dag_currency_l0_recovery_sync_refresh_pending",
              1L,
              Seq(Metrics.unsafeLabelName("mode") -> publication.mode)
            ) >>
              Metrics[F].incrementCounter(
                "dag_currency_l0_recovery_sync_refresh_total",
                Seq(
                  Metrics.unsafeLabelName("mode") -> publication.mode,
                  Metrics.unsafeLabelName("outcome") -> "restored"
                )
              )).attempt.void
        case Some(publication) if publication.expired =>
          // Expiry stops retries; it does not mean recovery succeeded. Keep the unresolved gauge
          // asserted until an exact retained-window confirmation clears the receipt or the operator
          // starts a newly anchored recovery.
          (Metrics[F].updateGauge(
            "dag_currency_l0_recovery_sync_refresh_pending",
            1L,
            Seq(Metrics.unsafeLabelName("mode") -> publication.mode)
          ) >>
            Metrics[F].updateGauge("dag_currency_l0_recovery_sync_selected_target_remaining_ordinals", 0L)).attempt.void >>
            logger.error(
              s"RECOVERY_SYNC_PUBLICATION_EXPIRED mode=${publication.mode} binaryHash=${publication.binaryHash} " +
                s"validThrough=${publication.validThroughGlobalParent}; a new rollback recovery is required"
            )
        case Some(publication) =>
          Async[F].raiseError[Unit](
            new IllegalStateException(
              s"Unreconciled recovery publication reached sender startup: binaryHash=${publication.binaryHash}"
            )
          )
        case None => Async[F].unit
      }
      _ <- startBackgroundWorker(sender, lastGlobalSnapshotStorage, logger)
    } yield sender
  }

  private def startBackgroundWorker[F[_]: Async](
    sender: StateChannelBinarySenderImpl[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    logger: SelfAwareStructuredLogger[F]
  )(implicit S: Supervisor[F]): F[Unit] = {

    // A single tick. Errors are swallowed and logged here so a transient failure (e.g. reading cluster/storage)
    // never tears down the stream, which would otherwise trigger the 1s restart path on every tick (restart loop).
    def tick: F[Unit] =
      lastGlobalSnapshotStorage.get.flatMap {
        case Some(snapshot) => sender.processQueue(snapshot)
        case None           => sender.processQueueWithoutSnapshot
      }
        .handleErrorWith(err => logger.warn(err)("[Queue] Processing tick failed; will retry on next tick"))

    def runWorker: F[Unit] =
      fs2.Stream
        .awakeEvery[F](5.seconds)
        .evalMap(_ => tick)
        .compile
        .drain
        .handleErrorWith { err =>
          logger.error(err)("[Queue] Worker crashed, restarting in 1 second") >>
            Temporal[F].sleep(1.second) >>
            runWorker
        }

    S.supervise(runWorker).void
  }
}

private[services] class StateChannelBinarySenderImpl[F[_]: Async: Hasher: Metrics](
  tracker: BinaryTracker[F],
  poster: BinaryPoster[F],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  identifierStorage: IdentifierStorage[F],
  cluster: ClusterStorage[F],
  selfId: PeerId,
  maxTrackedBinaries: Int,
  // How a posting effect is scheduled. Production forks it on a Supervisor (non-blocking, fire-and-forget);
  // tests pass identity to run it synchronously and observe ordering deterministically.
  forkSend: F[Unit] => F[Unit],
  logger: SelfAwareStructuredLogger[F],
  recoverySyncPublicationStorage: Option[RecoverySyncPublicationStorage[F]] = None,
  stateChannelBinaryOutboxStorage: Option[StateChannelBinaryOutboxStorage[F]] = None,
  publicationEnabled: Ref[F, Boolean],
  nodeMayPublish: F[Boolean],
  onRecoveryPublicationConfirmed: F[Unit],
  onCanonicalMismatch: StateChannelBinaryOutboxStorage.CanonicalTipMismatch => F[Unit]
) extends StateChannelBinarySender[F] {

  // Max pending binaries inspected per normal-mode tick (chain order). Retry mode uses the adaptive `cap`.
  private val normalSendWindow = 10
  // Re-post an unconfirmed binary if our last attempt was at least this many global ordinals ago. Avoids both
  // stranding a delivered-but-unconfirmed binary (we keep re-sending until it is confirmed) and hammering the L0.
  private val resendIntervalOrdinals = 2L
  private val publicationDrainPoll = 50.millis
  private val publicationDrainTimeout = 30.seconds

  private def awaitInFlightDrain: F[Unit] = {
    def loop: F[Unit] =
      tracker.getState.flatMap { state =>
        if (state.inFlight.isEmpty) Async[F].unit
        else Temporal[F].sleep(publicationDrainPoll) >> loop
      }

    Temporal[F].timeoutTo(
      loop,
      publicationDrainTimeout,
      new IllegalStateException(
        s"Currency binary publication did not quiesce within $publicationDrainTimeout; canonical replacement is blocked"
      ).raiseError[F, Unit]
    )
  }

  def disablePublishing: F[Unit] =
    publicationEnabled.set(false) >>
      awaitInFlightDrain >>
      logger.info("[Queue] Currency binary publication disabled while canonical Currency state is unresolved")

  def enablePublishing: F[Unit] =
    publicationEnabled.set(true) >>
      logger.info("[Queue] Currency binary publication enabled after canonical Currency state reconciliation")

  private def publicationAllowed: F[Boolean] =
    (publicationEnabled.get, nodeMayPublish).mapN(_ && _)

  /** The durable outbox is authoritative; BinaryTracker is only a bounded active window. Refill available slots in Currency ordinal order
    * after startup, clear, and every confirmation.
    */
  private[services] def refillFromOutbox: F[Unit] =
    stateChannelBinaryOutboxStorage.traverse_ { outbox =>
      for {
        quarantinedHashes <- recoverySyncPublicationStorage.fold(Set.empty[Hash].pure[F])(
          _.get.map(_.filter(_.expired).map(_.binaryHash).toSet)
        )
        trackerState <- tracker.getState
        available = math.max(0, maxTrackedBinaries - trackerState.tracked.size)
        trackedHashes = trackerState.tracked.map {
          case pending: PendingBinary     => pending.binary.hash
          case confirmed: ConfirmedBinary => confirmed.pendingBinary.binary.hash
        }.toSet
        // The exact recovery binary is also an ordinary durable entry. Once its
        // retained-window deadline expires, keep the diagnostic receipt but never
        // let ordinary refill/startup/clearPending silently re-arm publication.
        entries <- outbox.getCommitted(trackedHashes ++ quarantinedHashes, available)
        currentGlobalOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
        _ <- entries.traverse_ { entry =>
          tracker
            .enqueue(
              Hashed(entry.binary, entry.binaryHash, entry.proofsHash),
              entry.currencySnapshotOrdinal,
              currentGlobalOrdinal
            )
            .flatMap { enqueued =>
              Async[F].raiseUnless(enqueued)(
                new IllegalStateException(
                  s"Unable to refill durable Currency binary outbox ordinal=${entry.currencySnapshotOrdinal} hash=${entry.binaryHash}"
                )
              )
            }
        }
        durable <- outbox.stats
        active <- tracker.getState
        activeEntries = active.tracked.toList.map {
          case pending: PendingBinary     => pending
          case confirmed: ConfirmedBinary => confirmed.pendingBinary
        }
        activePayloadBytes = activeEntries.iterator.map(_.binary.signed.value.content.length.toLong).sum
        _ <- (Metrics[F].updateGauge("dag_currency_l0_binary_outbox_pending_count", durable.pendingCount.toLong) >>
          Metrics[F].updateGauge("dag_currency_l0_binary_outbox_serialized_bytes", durable.serializedBytes) >>
          Metrics[F].updateGauge(
            "dag_currency_l0_binary_outbox_oldest_ordinal",
            durable.oldestOrdinal.fold(0L)(_.value.value)
          ) >>
          Metrics[F].updateGauge(
            "dag_currency_l0_binary_outbox_newest_ordinal",
            durable.newestOrdinal.fold(0L)(_.value.value)
          ) >>
          Metrics[F].updateGauge("dag_currency_l0_binary_outbox_active_count", activeEntries.size.toLong) >>
          Metrics[F].updateGauge("dag_currency_l0_binary_outbox_active_payload_bytes", activePayloadBytes)).attempt.void
      } yield ()
    }

  def enqueue(
    binary: Hashed[StateChannelSnapshotBinary],
    currencySnapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Unit] =
    for {
      currentGlobalOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
      enqueued <- tracker.enqueue(binary, currencySnapshotOrdinal, currentGlobalOrdinal)
      _ <-
        if (enqueued)
          // Observability cannot turn a successful queue mutation into a failed retained effect.
          logger.info(s"[Queue] Enqueued binary ${binary.hash} at ordinal $currencySnapshotOrdinal").attempt.void
        else {
          val observeBackpressure =
            logger.error(
              s"[Queue] Send queue is full (bound=$maxTrackedBinaries reached); backpressuring binary ${binary.hash} " +
                s"at ordinal $currencySnapshotOrdinal until the chain head drains."
            ) >> updateBackpressuredStateChannelBinaryMetrics()

          // The binary chain is hash-linked. Treating a full queue as success would clear the
          // retained Finished effect and permanently skip this link. The independent queue worker
          // can still drain existing entries while finalization retries this exact binary.
          observeBackpressure.attempt.void >>
            Async[F].raiseError[Unit](
              new IllegalStateException(s"State-channel binary queue is full (bound=$maxTrackedBinaries)")
            )
        }
    } yield ()

  def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] =
    for {
      identifier <- identifierStorage.get
      confirmedHashes <- getConfirmedHashes(identifier, globalSnapshot)
      canonicalTip <- lastGlobalSnapshotStorage.getCombined.map(_.flatMap {
        case (_, info) =>
          (info.lastCurrencySnapshots.get(identifier), info.lastStateChannelSnapshotHashes.get(identifier)).mapN {
            case (currencySnapshot, binaryHash) =>
              val ordinal = currencySnapshot.fold(_.ordinal, _._1.ordinal)
              ordinal -> binaryHash
          }
      })
      proof = GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
      // Whole confirmation transition applied atomically so a concurrent enqueue/worker can't act on torn state.
      metricsState <- tracker.modify { state =>
        val oldRetryMode = state.retryMode
        val confirmed = BinaryTracker.markConfirmedUpToHighest(state, confirmedHashes, proof)
        val retryMode = RetryStrategy.shouldEnterRetryMode(confirmed, globalSnapshot.ordinal)
        val withMode = confirmed.copy(retryMode = retryMode)
        val withParams = RetryStrategy.updateRetryParameters(withMode, oldRetryMode)
        val pruned = BinaryTracker.pruneConfirmed(withParams)
        (pruned, pruned)
      }
      _ <- updateStateChannelRetryParametersMetrics(metricsState)
      incrementallyConfirmed <- stateChannelBinaryOutboxStorage.fold(
        List.empty[StateChannelBinaryOutboxStorage.Entry].pure[F]
      )(_.confirm(confirmedHashes))
      canonicallyConfirmed <- stateChannelBinaryOutboxStorage.fold(
        List.empty[StateChannelBinaryOutboxStorage.Entry].pure[F]
      )(outbox =>
        canonicalTip
          .fold(List.empty[StateChannelBinaryOutboxStorage.Entry].pure[F]) {
            case (currencyOrdinal, binaryHash) =>
              outbox.confirmCanonicalTip(currencyOrdinal, binaryHash)
          }
          .handleErrorWith {
            case error: StateChannelBinaryOutboxStorage.CanonicalTipMismatch =>
              disablePublishing >> onCanonicalMismatch(error) >> error.raiseError[F, List[StateChannelBinaryOutboxStorage.Entry]]
            case error => error.raiseError[F, List[StateChannelBinaryOutboxStorage.Entry]]
          }
      )
      // The exact incremental may have aged out before this node restarts. Canonical GSI
      // confirmation must drain both durable storage and the bounded active window;
      // otherwise old entries occupy every tracker slot and newer outbox pages starve.
      _ <- (incrementallyConfirmed ++ canonicallyConfirmed).iterator
        .map(_.binaryHash)
        .toSet
        .toList
        .traverse_(tracker.remove)
      _ <- refillFromOutbox
      _ <- confirmRecoveryPublication(confirmedHashes ++ canonicalTip.map(_._2), globalSnapshot.ordinal)
    } yield ()

  def clearPending: F[Unit] =
    logger.info("[Queue] Clearing all pending binaries") >>
      tracker.clear >>
      // A soft reset may clear process-local retry state, but it must immediately restore every
      // durable, locally committed ordinary binary. Waiting for a JVM restart would create a
      // publication gap in the hash-linked Currency chain.
      refillFromOutbox >>
      // The recovery receipt carries an additional deadline and may duplicate an ordinary
      // entry. BinaryTracker de-duplicates by exact hash.
      recoverySyncPublicationStorage.traverse_ {
        _.get.flatMap {
          case Some(publication) if publication.locallyCommitted && !publication.expired =>
            enqueue(
              Hashed(publication.binary, publication.binaryHash, publication.proofsHash),
              publication.currencySnapshotOrdinal,
              none
            ) >>
              Metrics[F]
                .incrementCounter(
                  "dag_currency_l0_recovery_sync_refresh_total",
                  Seq(
                    Metrics.unsafeLabelName("mode") -> publication.mode,
                    Metrics.unsafeLabelName("outcome") -> "restored_after_clear"
                  )
                )
                .attempt
                .void
          case _ => Async[F].unit
        }
      }

  def processQueue(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] =
    publicationAllowed.ifM(processEnabledQueue(globalSnapshot), Applicative[F].unit)

  private def processEnabledQueue(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = {
    val signers = globalSnapshot.signed.proofs.map(_.id.toPeerId).some
    val ordinal = globalSnapshot.ordinal
    for {
      // The background worker races the incremental-snapshot handler. Check this snapshot for the
      // exact recovery binary before applying its ordinal as an expiry boundary, so a publication
      // included at the last usable opportunity can never be reported as expired merely because
      // the queue tick ran first.
      _ <- confirmRecoveryPublicationFrom(globalSnapshot)
      expiredRecovery <- recoverySyncPublicationStorage.fold(none[RecoverySyncPublicationStorage.Publication].pure[F])(
        _.expireAt(ordinal)
      )
      _ <- expiredRecovery.traverse_ { publication =>
        tracker.remove(publication.binaryHash) >>
          (Metrics[F].incrementCounter(
            "dag_currency_l0_recovery_sync_refresh_total",
            Seq(
              Metrics.unsafeLabelName("mode") -> publication.mode,
              Metrics.unsafeLabelName("outcome") -> "expired"
            )
          ) >>
            // The receipt remains unresolved after expiry. Only exact canonical GL0 confirmation
            // clears refresh_pending; otherwise an operator could mistake a stopped retry for success.
            Metrics[F].updateGauge("dag_currency_l0_recovery_sync_selected_target_remaining_ordinals", 0L)).attempt.void >>
          logger.error(
            s"RECOVERY_SYNC_PUBLICATION_EXPIRED mode=${publication.mode} binaryHash=${publication.binaryHash} " +
              s"globalParent=$ordinal validThrough=${publication.validThroughGlobalParent}; stopping retries"
          )
      }
      alive <- aliveSet
      state <- tracker.getState
      _ <-
        if (state.retryMode) processRetryMode(state.cap.value.toInt, signers, ordinal.some, alive)
        else processNormalMode(signers, ordinal.some, alive)
    } yield ()
  }

  private def confirmRecoveryPublicationFrom(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] =
    recoverySyncPublicationStorage.fold(Applicative[F].unit)(
      _.get.flatMap {
        case Some(_) =>
          identifierStorage.get
            .flatMap(getConfirmedHashes(_, globalSnapshot))
            .flatMap(confirmRecoveryPublication(_, globalSnapshot.ordinal))
        case None => Applicative[F].unit
      }
    )

  private[services] def confirmRecoveryPublication(confirmedHashes: Set[Hash], globalOrdinal: SnapshotOrdinal): F[Unit] =
    recoverySyncPublicationStorage
      .fold(none[RecoverySyncPublicationStorage.Publication].pure[F])(_.confirm(confirmedHashes))
      .flatMap(
        _.traverse_ { publication =>
          onRecoveryPublicationConfirmed >>
            (Metrics[F].updateGauge(
              "dag_currency_l0_recovery_sync_refresh_pending",
              0L,
              Seq(Metrics.unsafeLabelName("mode") -> publication.mode)
            ) >>
              Metrics[F].incrementCounter(
                "dag_currency_l0_recovery_sync_refresh_total",
                Seq(
                  Metrics.unsafeLabelName("mode") -> publication.mode,
                  Metrics.unsafeLabelName("outcome") -> "gl0_confirmed"
                )
              )).attempt.void >>
            logger.info(
              s"RECOVERY_SYNC_PUBLICATION_CONFIRMED mode=${publication.mode} binaryHash=${publication.binaryHash} " +
                s"globalOrdinal=$globalOrdinal"
            )
        }
      )

  def processQueueWithoutSnapshot: F[Unit] =
    publicationAllowed.ifM(
      for {
        alive <- aliveSet
        state <- tracker.getState
        _ <-
          if (!state.retryMode) processNormalMode(none, none, alive)
          else logger.info("[Queue] Retry mode active but no global snapshot available")
      } yield (),
      Applicative[F].unit
    )

  // Responsive metagraph peers plus self. None means "liveness unknown this tick" -> selection does not filter,
  // degrading to the pure deterministic behaviour rather than wrongly treating everyone as dead.
  private def aliveSet: F[Option[Set[PeerId]]] =
    cluster.getResponsivePeers
      .map(peers => (peers.toList.map(_.id).toSet + selfId).some)
      .handleErrorWith { err =>
        logger
          .warn(err)("[Queue] Could not read responsive peers; not filtering by liveness this tick")
          .as(none[Set[PeerId]])
      }

  private def processNormalMode(
    signers: Option[NonEmptySet[PeerId]],
    currentOrdinal: Option[SnapshotOrdinal],
    alive: Option[Set[PeerId]]
  ): F[Unit] =
    tracker.getPendingToRetry(normalSendWindow).flatMap { pending =>
      val due = pending.filter(p => isDueForResend(p, currentOrdinal))
      if (due.nonEmpty)
        logger.info(s"[Queue] Normal mode: ${due.size} binaries due for (re)send") >>
          due.traverse_(p => attemptSelfSend(p, signers, alive, currentOrdinal, force = false))
      else
        Applicative[F].unit
    }

  private def processRetryMode(
    cap: Int,
    signers: Option[NonEmptySet[PeerId]],
    currentOrdinal: Option[SnapshotOrdinal],
    alive: Option[Set[PeerId]]
  ): F[Unit] =
    tracker.getPendingToRetry(cap).flatMap { toRetry =>
      // Chain order; in retry mode every permitted node escalates to sending (force = true) so a binary whose
      // deterministic owner is unavailable still reaches the global L0 (de-duplicated there by hash).
      val sorted = toRetry.sortBy(_.currencySnapshotOrdinal.value.value)
      if (sorted.nonEmpty)
        logger.info(s"[RetryMode] Escalating send of ${sorted.size} binaries") >>
          sorted.traverse_(p => attemptSelfSend(p, signers, alive, currentOrdinal, force = true))
      else
        Applicative[F].unit
    }

  private def isDueForResend(p: PendingBinary, currentOrdinal: Option[SnapshotOrdinal]): Boolean =
    p.lastAttemptAtOrdinal match {
      case None       => true
      case Some(last) => currentOrdinal.exists(o => o.value.value - last.value.value >= resendIntervalOrdinals)
    }

  private def attemptSelfSend(
    p: PendingBinary,
    signers: Option[NonEmptySet[PeerId]],
    alive: Option[Set[PeerId]],
    currentOrdinal: Option[SnapshotOrdinal],
    force: Boolean
  ): F[Unit] =
    publicationAllowed.ifM(poster.shouldSelfSend(p.binary, alive, force), false.pure[F]).flatMap {
      case false => Applicative[F].unit
      case true =>
        tracker.tryBeginSend(p.binary.hash, currentOrdinal).flatMap {
          case false => Applicative[F].unit // already in flight on this node
          case true  =>
            // Re-check after claiming. If disablePublishing raced the first gate read,
            // either this check observes the closed gate and releases immediately, or
            // the in-flight claim is visible to disablePublishing's drain barrier before
            // canonical storage can be replaced.
            publicationAllowed.ifM(
              // .guarantee releases the in-flight claim when the (forked) send finishes or is cancelled; the outer
              // handleErrorWith covers the rare case where scheduling the fork itself fails, so the claim never leaks.
              forkSend(
                poster
                  .sendSelf(p.binary, signers)
                  .flatMap(_ => logger.info(s"[Queue] Posted ${p.binary.hash}"))
                  .handleErrorWith(err => logger.warn(s"[Queue] Failed to post ${p.binary.hash}: ${err.getMessage}"))
                  .guarantee(tracker.endSend(p.binary.hash))
              ).handleErrorWith(err =>
                logger.warn(err)(s"[Queue] Could not schedule send for ${p.binary.hash}") >> tracker.endSend(p.binary.hash)
              ),
              tracker.endSend(p.binary.hash)
            )
        }
    }

  private def getConfirmedHashes(
    identifier: Address,
    globalSnapshot: Hashed[GlobalIncrementalSnapshot]
  ): F[Set[Hash]] = {
    val binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
    binaries.traverse(_.toHashed).map(_.map(_.hash)).map(_.toSet)
  }
}
