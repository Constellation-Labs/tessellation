package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.std.Supervisor
import cats.effect.syntax.all._
import cats.effect.{Async, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.metrics.{updateDroppedStateChannelBinaryMetrics, updateStateChannelRetryParametersMetrics}
import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

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
    maxTrackedBinaries: Int = 10000
  )(implicit S: Supervisor[F]): F[StateChannelBinarySender[F]] = {
    val logger = Slf4jLogger.getLoggerFromName(this.getClass.getName)

    for {
      tracker <- BinaryTracker.make[F](maxTrackedBinaries)
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
        logger
      )
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
  logger: SelfAwareStructuredLogger[F]
) extends StateChannelBinarySender[F] {

  // Max pending binaries inspected per normal-mode tick (chain order). Retry mode uses the adaptive `cap`.
  private val normalSendWindow = 10
  // Re-post an unconfirmed binary if our last attempt was at least this many global ordinals ago. Avoids both
  // stranding a delivered-but-unconfirmed binary (we keep re-sending until it is confirmed) and hammering the L0.
  private val resendIntervalOrdinals = 2L

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
          logger.info(s"[Queue] Enqueued binary ${binary.hash} at ordinal $currencySnapshotOrdinal")
        else
          logger.error(
            s"[Queue] Send queue is full (bound=$maxTrackedBinaries reached); dropping binary ${binary.hash} " +
              s"at ordinal $currencySnapshotOrdinal. The chain head is not draining; metagraph may require resync."
          ) >> updateDroppedStateChannelBinaryMetrics()
    } yield ()

  def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] =
    for {
      identifier <- identifierStorage.get
      confirmedHashes <- getConfirmedHashes(identifier, globalSnapshot)
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
    } yield ()

  def clearPending: F[Unit] =
    logger.info("[Queue] Clearing all pending binaries") >> tracker.clear

  def processQueue(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = {
    val signers = globalSnapshot.signed.proofs.map(_.id.toPeerId).some
    val ordinal = globalSnapshot.ordinal
    for {
      alive <- aliveSet
      state <- tracker.getState
      _ <-
        if (state.retryMode) processRetryMode(state.cap.value.toInt, signers, ordinal.some, alive)
        else processNormalMode(signers, ordinal.some, alive)
    } yield ()
  }

  def processQueueWithoutSnapshot: F[Unit] =
    for {
      alive <- aliveSet
      state <- tracker.getState
      _ <-
        if (!state.retryMode) processNormalMode(none, none, alive)
        else logger.info("[Queue] Retry mode active but no global snapshot available")
    } yield ()

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
    poster.shouldSelfSend(p.binary, alive, force).flatMap {
      case false => Applicative[F].unit
      case true =>
        tracker.tryBeginSend(p.binary.hash, currentOrdinal).flatMap {
          case false => Applicative[F].unit // already in flight on this node
          case true  =>
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
