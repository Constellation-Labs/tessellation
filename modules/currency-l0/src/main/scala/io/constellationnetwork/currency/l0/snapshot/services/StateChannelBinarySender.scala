package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.std.Supervisor
import cats.effect.{Async, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.metrics.updateStateChannelRetryParametersMetrics
import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
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
    customPeersAllowanceList: Option[Set[AllowanceListEntry]]
  )(implicit S: Supervisor[F]): F[StateChannelBinarySender[F]] = {
    val logger = Slf4jLogger.getLoggerFromName(this.getClass.getName)

    for {
      tracker <- BinaryTracker.make[F]
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
        logger
      )
      _ <- startBackgroundWorker(sender, lastGlobalSnapshotStorage, logger)
    } yield sender
  }

  private def startBackgroundWorker[F[_]: Async](
    sender: StateChannelBinarySenderImpl[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    logger: org.typelevel.log4cats.SelfAwareStructuredLogger[F]
  )(implicit S: Supervisor[F]): F[Unit] = {

    def runWorker: F[Unit] =
      fs2.Stream
        .awakeEvery[F](5.seconds)
        .evalMap(_ =>
          lastGlobalSnapshotStorage.get.flatMap {
            case Some(snapshot) =>
              sender.processQueue(snapshot)
            case None =>
              sender.processQueueWithoutSnapshot
          }
        )
        .compile
        .drain
        .handleErrorWith { err =>
          logger.error(err)("[Queue] Worker crashed, restarting in 1 second") >>
            Temporal[F].sleep(1.second) >>
            runWorker
        }

    S.supervise(runWorker).void
  }

  private class StateChannelBinarySenderImpl[F[_]: Async: Hasher: Metrics](
    tracker: BinaryTracker[F],
    poster: BinaryPoster[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    identifierStorage: IdentifierStorage[F],
    logger: org.typelevel.log4cats.SelfAwareStructuredLogger[F]
  )(implicit S: Supervisor[F])
      extends StateChannelBinarySender[F] {

    def enqueue(
      binary: Hashed[StateChannelSnapshotBinary],
      currencySnapshotOrdinal: SnapshotOrdinal,
      lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
    ): F[Unit] =
      for {
        currentGlobalOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
        _ <- tracker.enqueue(binary, currencySnapshotOrdinal, currentGlobalOrdinal)
        _ <- logger.info(s"[Queue] Enqueued binary ${binary.hash} at ordinal $currencySnapshotOrdinal")
      } yield ()

    def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] =
      for {
        identifier <- identifierStorage.get
        confirmedHashes <- getConfirmedHashes(identifier, globalSnapshot)
        state <- tracker.getState
        oldRetryMode = state.retryMode
        proof = GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
        _ <- tracker.markAsConfirmed(confirmedHashes, proof)
        updatedState <- tracker.getState
        retryMode = RetryStrategy.shouldEnterRetryMode(updatedState, globalSnapshot.ordinal)
        _ <- tracker.updateState(_.copy(retryMode = retryMode))
        _ <- tracker.updateState(RetryStrategy.updateRetryParameters(_, oldRetryMode))
        _ <- tracker.pruneConfirmed
        metricsState <- tracker.getState
        _ <- updateStateChannelRetryParametersMetrics(metricsState)
      } yield ()

    def clearPending: F[Unit] =
      logger.info("[Queue] Clearing all pending binaries") >> tracker.clear

    def processQueue(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = {
      val lastGlobalSnapshotSigners = globalSnapshot.signed.proofs.map(_.id.toPeerId).some
      tracker.getState.flatMap { state =>
        if (state.retryMode) {
          processRetryMode(state.cap.value.toInt, lastGlobalSnapshotSigners)
        } else {
          processNormalMode(lastGlobalSnapshotSigners)
        }
      }
    }

    def processQueueWithoutSnapshot: F[Unit] =
      tracker.getState.flatMap { state =>
        if (!state.retryMode) {
          processNormalMode(none)
        } else {
          logger.info("[Queue] Retry mode active but no global snapshot available") >>
            Applicative[F].unit
        }
      }

    private def processNormalMode(signers: Option[NonEmptySet[PeerId]]): F[Unit] =
      tracker.getPendingToRetry(10).flatMap { pending =>
        val unsent = pending.filter(_.sendsSoFar.value === 0L)
        if (unsent.nonEmpty) {
          logger.info(s"[Queue] Processing ${unsent.size} unsent binaries") >>
            unsent.traverse_(p => sendBinaryInBackground(p, signers))
        } else {
          Applicative[F].unit
        }
      }

    private def processRetryMode(cap: Int, signers: Option[NonEmptySet[PeerId]]): F[Unit] =
      tracker.getPendingToRetry(cap).flatMap { toRetry =>
        val sorted = toRetry.sortBy(_.currencySnapshotOrdinal.value.value)
        if (sorted.nonEmpty) {
          logger.info(s"[RetryMode] Processing ${sorted.size} binaries") >>
            sorted.traverse_(p => sendBinaryInBackground(p, signers))
        } else {
          Applicative[F].unit
        }
      }

    private def sendBinaryInBackground(
      pending: PendingBinary,
      signers: Option[NonEmptySet[PeerId]] = none
    ): F[Unit] =
      S.supervise(
        poster
          .post(pending.binary, signers)
          .flatMap(peerId => logger.info(s"[Queue] Sent ${pending.binary.hash} via $peerId"))
          .handleErrorWith(err => logger.warn(s"[Queue] Failed to send ${pending.binary.hash}: ${err.getMessage}"))
      ).void

    private def getConfirmedHashes(
      identifier: Address,
      globalSnapshot: Hashed[GlobalIncrementalSnapshot]
    ): F[Set[Hash]] = {
      val binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
      binaries.traverse(_.toHashed).map(_.map(_.hash)).map(_.toSet)
    }
  }
}
