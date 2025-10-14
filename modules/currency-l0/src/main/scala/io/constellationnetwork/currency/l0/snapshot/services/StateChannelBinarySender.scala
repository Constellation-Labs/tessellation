package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.{Async, Ref}
import cats.syntax.all._

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
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait StateChannelBinarySender[F[_]] {
  def process(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Unit]

  def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit]

  def processPending(
    globalSnapshot: Hashed[GlobalIncrementalSnapshot],
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Unit]

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
  ): F[StateChannelBinarySender[F]] = {
    def logger = Slf4jLogger.getLoggerFromName(this.getClass.getName)
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
    } yield
      new StateChannelBinarySender[F] {

        def process(
          binary: Hashed[StateChannelSnapshotBinary],
          lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
        ): F[Unit] =
          for {
            currentOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
            state <- tracker.getState
            _ <- tracker.enqueue(binary, currentOrdinal)
            _ <-
              if (state.retryMode) {
                logger.warn(s"[RetryMode] Snapshot binary of hash ${binary.hash} enqueued.")
              } else {
                logger.info(s"Starting to send binary ${binary.hash} to GL0") >>
                  poster.post(binary, lastGlobalSnapshotSigners).flatMap { peerId =>
                    logger.info(s"Peer selected to send currency snapshot to GL0: $peerId") >>
                      peerId.traverse_ { pid =>
                        if (pid === selfId) {
                          logger.info(s"Snapshot binary of hash ${binary.hash} enqueued and sent to GL0")
                        } else {
                          Applicative[F].unit
                        }
                      }
                  }
              }
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
            metricsState <- tracker.getState
            _ <- updateStateChannelRetryParametersMetrics(metricsState)
          } yield ()

        def processPending(
          globalSnapshot: Hashed[GlobalIncrementalSnapshot],
          globalSnapshotInfo: GlobalSnapshotInfo
        ): F[Unit] =
          tracker.getState.flatMap { state =>
            if (state.retryMode) {
              for {
                toRetry <- tracker.getPendingToRetry(state.cap.value.toInt)
                _ <- logger.warn(s"[RetryMode] Retrying ${toRetry.size} pending binaries").whenA(toRetry.nonEmpty)
                lastGlobalSnapshotSigners = globalSnapshot.signed.proofs.map(_.id.toPeerId)
                _ <- toRetry.traverse_(pending => poster.post(pending.binary, lastGlobalSnapshotSigners.some))
              } yield ()
            } else {
              Applicative[F].unit
            }
          }

        def clearPending: F[Unit] = tracker.clear

        private def getConfirmedHashes(
          identifier: Address,
          globalSnapshot: Hashed[GlobalIncrementalSnapshot]
        ): F[Set[io.constellationnetwork.security.hash.Hash]] = {
          val binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
          binaries.traverse(_.toHashed).map(_.map(_.hash)).map(_.toSet)
        }
      }
  }
}
