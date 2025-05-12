package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.Queue

import io.constellationnetwork.currency.l0.metrics.updateStateChannelRetryParametersMetrics
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment._
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.node.shared.infrastructure.statechannel.StateChannelAllowanceLists
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.{L0Peer, PeerId}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.all.NonNegLong
import eu.timepit.refined.types.numeric.PosLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._

case class GlobalSnapshotConfirmationProof(globalHash: Hash, globalOrdinal: SnapshotOrdinal, globalEpochProgress: EpochProgress)

object GlobalSnapshotConfirmationProof {
  def fromGlobalSnapshot(snapshot: Hashed[GlobalIncrementalSnapshot]): GlobalSnapshotConfirmationProof =
    GlobalSnapshotConfirmationProof(snapshot.hash, snapshot.ordinal, snapshot.epochProgress)
}

@derive(eqv)
sealed trait TrackedBinary

case class PendingBinary(
  binary: Hashed[StateChannelSnapshotBinary],
  enqueuedAtOrdinal: SnapshotOrdinal,
  sendsSoFar: NonNegLong
) extends TrackedBinary

case class ConfirmedBinary(
  pendingBinary: PendingBinary,
  confirmationProof: GlobalSnapshotConfirmationProof
) extends TrackedBinary

case class State(
  tracked: Queue[TrackedBinary],
  cap: NonNegLong,
  retryMode: Boolean,
  noConfirmationsSinceRetryCount: NonNegLong,
  backoffExponent: NonNegLong
)

object State {
  def empty: State = State(
    tracked = Queue.empty[TrackedBinary],
    cap = 4L,
    retryMode = false,
    noConfirmationsSinceRetryCount = 0L,
    backoffExponent = 0L
  )
}

trait StateChannelBinarySender[F[_]] {
  def processPending(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit]

  def clearPending: F[Unit]

  def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit]

  def process(binaryHashed: Hashed[StateChannelSnapshotBinary], lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]): F[Unit]
}

object StateChannelBinarySender {
  def make[F[_]: Async: Hasher: Metrics](
    identifierStorage: IdentifierStorage[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    selfId: PeerId,
    environment: AppEnvironment
  ): F[StateChannelBinarySender[F]] =
    Ref
      .of[F, State](State.empty)
      .map(
        make[F](
          _,
          identifierStorage,
          globalL0ClusterStorage,
          lastGlobalSnapshotStorage,
          stateChannelSnapshotClient,
          stateChannelAllowanceLists,
          selfId,
          environment
        )
      )

  def make[F[_]: Async: Hasher: Metrics](
    stateR: Ref[F, State],
    identifierStorage: IdentifierStorage[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    selfId: PeerId,
    environment: AppEnvironment
  ): StateChannelBinarySender[F] =
    new StateChannelBinarySender[F] {

      private val noConfirmationsToTriggerRetryMode: PosLong = 5L
      private val confirmedCountMultiplier: PosLong = 4L
      private val allowedEmptyAllowanceList = List(Dev, Testnet, Integrationnet)

      private val logger = Slf4jLogger.getLogger

      def process(
        binary: Hashed[StateChannelSnapshotBinary],
        lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
      ): F[Unit] =
        lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue)).flatMap { currentOrdinal =>
          stateR.modify {
            case state @ State(pending, _, retryMode, _, _) =>
              val updatedPending = pending :+ PendingBinary(binary, enqueuedAtOrdinal = currentOrdinal, 0L)
              val action =
                if (retryMode) logger.warn(s"[RetryMode] Snapshot binary of hash ${binary.hash} enqueued.")
                else
                  post(binary, lastGlobalSnapshotSigners).flatTap(_ =>
                    logger.info(s"Snapshot binary of hash ${binary.hash} enqueued and sent to GL0")
                  )
              val newState = state.copy(tracked = updatedPending)
              (newState, action)
          }.flatMap(identity)
        }

      def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = for {
        identifier <- identifierStorage.get
        confirmedHashes <- getConfirmedMetagraphBinaryHashesFromGlobalSnapshot(identifier, globalSnapshot)
        _ <- stateR.update { state =>
          val oldRetryMode = state.retryMode
          val updatedTrackedState = updateTrackedItems(state, confirmedHashes, globalSnapshot)
          val retryState = updateRetryMode(updatedTrackedState, globalSnapshot.ordinal)
          updateRetryParameters(retryState, oldRetryMode)
        }

        updatedState <- stateR.get
        _ <- updateStateChannelRetryParametersMetrics(updatedState)
      } yield ()

      def processPending(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = stateR.get.flatMap { state =>
        if (state.retryMode) {
          val lastGlobalSnapshotSigners = globalSnapshot.signed.proofs.map(_.id.toPeerId)
          val toRetry = state.tracked.collect { case pendingBinary: PendingBinary => pendingBinary }.take(state.cap.toInt)
          logger.warn(s"[RetryMode] Retrying ${toRetry.size} pending binaries").whenA(toRetry.nonEmpty) >> toRetry.traverse_(tracked =>
            post(tracked.binary, lastGlobalSnapshotSigners.some)
          )
        } else Applicative[F].unit
      }

      def clearPending: F[Unit] = stateR.set(State.empty)

      private def getConfirmedMetagraphBinaryHashesFromGlobalSnapshot(
        identifier: Address,
        globalSnapshot: Hashed[GlobalIncrementalSnapshot]
      ): F[Set[Hash]] = {
        val binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
        binaries.traverse(_.toHashed).map(_.map(_.hash)).map(_.toSet)
      }

      private def updateTrackedItems(
        state: State,
        confirmedHashes: Set[Hash],
        globalSnapshot: Hashed[GlobalIncrementalSnapshot]
      ): State = {
        val indexedTracked = state.tracked.zipWithIndex

        val maybeHighestConfirmationIndex = indexedTracked.collect {
          case (PendingBinary(tracked, _, _), index) if confirmedHashes.contains(tracked.hash) => index
        }.maxOption

        val updatedTracked = indexedTracked.map {
          case (pendingBinary @ PendingBinary(_, _, _), index) if index <= maybeHighestConfirmationIndex.getOrElse(-1) =>
            ConfirmedBinary(pendingBinary, GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot))
          case (other, _) => other
        }

        state.copy(tracked = updatedTracked)
      }

      private def updateRetryMode(
        state: State,
        currentOrdinal: SnapshotOrdinal
      ): State = {
        val hasStalled = state.tracked.exists {
          case PendingBinary(_, enqueuedAtOrdinal, _) =>
            currentOrdinal.value - enqueuedAtOrdinal.value >= noConfirmationsToTriggerRetryMode
          case _ => false
        }

        val updatedRetryMode = if (!state.retryMode) {
          hasStalled
        } else {
          val pendingCount = state.tracked.collect { case _: PendingBinary => 1 }.sum
          val allPendingAlreadySent = state.tracked.forall {
            case PendingBinary(_, _, sendsSoFar) => sendsSoFar >= 1
            case _                               => true
          }

          if (pendingCount <= state.cap && allPendingAlreadySent && !hasStalled)
            false
          else
            true
        }

        state.copy(retryMode = updatedRetryMode)
      }

      private def updateRetryParameters(
        state: State,
        previousRetryMode: Boolean
      ): State =
        if ((!state.retryMode && previousRetryMode) || state.tracked.isEmpty) {
          State.empty.copy(tracked = state.tracked)
        } else if (!state.retryMode) {
          state
        } else {
          val confirmedCount = state.tracked.count(_.isInstanceOf[ConfirmedBinary])

          if (confirmedCount > 0) {
            val maxCap = confirmedCount * confirmedCountMultiplier
            val log2 = (x: Double) => Math.log10(x) / Math.log10(2.0)
            val surplus = NonNegLong.from {
              Math.ceil(log2(state.tracked.length.toDouble)).toLong
            }.getOrElse(NonNegLong.MinValue)
            val proposedCap = state.cap + surplus
            val updatedCap = NonNegLong.from(Math.min(proposedCap, maxCap)).getOrElse(NonNegLong.MinValue)

            state.copy(
              cap = updatedCap,
              backoffExponent = NonNegLong(0L),
              noConfirmationsSinceRetryCount = NonNegLong(0L)
            )
          } else if (state.cap > 1) {
            state.copy(
              cap = NonNegLong.from(state.cap - 1).getOrElse(NonNegLong.MinValue),
              backoffExponent = NonNegLong(0L),
              noConfirmationsSinceRetryCount = NonNegLong(0L)
            )
          } else if (state.cap.value == 1) {
            state.copy(
              cap = NonNegLong(0L),
              backoffExponent = NonNegLong.from(state.backoffExponent + 1L).getOrElse(NonNegLong.MaxValue),
              noConfirmationsSinceRetryCount = NonNegLong(1L)
            )
          } else {
            val noConfirmationsSinceRetryCount =
              NonNegLong.from(state.noConfirmationsSinceRetryCount + 1).getOrElse(NonNegLong.MaxValue)
            val updatedCap =
              if (noConfirmationsSinceRetryCount.toLong >= Math.ceil(Math.pow(2.0, state.backoffExponent.toDouble)).toLong)
                NonNegLong(1L)
              else state.cap

            state.copy(
              cap = updatedCap,
              noConfirmationsSinceRetryCount = noConfirmationsSinceRetryCount
            )
          }
        }

      private def post(
        binary: Hashed[StateChannelSnapshotBinary],
        lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
      ): F[Unit] = {
        val sendRetries = 5

        val retryPolicy: RetryPolicy[F] = RetryPolicies.limitRetries(sendRetries)

        def wasSuccessful: Either[NonEmptyList[StateChannelValidationError], Unit] => F[Boolean] =
          _.isRight.pure[F]

        def onFailure(binaryHashed: Hashed[StateChannelSnapshotBinary]) =
          (_: Either[NonEmptyList[StateChannelValidationError], Unit], details: RetryDetails) =>
            logger.info(s"Retrying sending ${binaryHashed.hash.show} to Global L0 after rejection. Retries so far ${details.retriesSoFar}")

        def onError(binaryHashed: Hashed[StateChannelSnapshotBinary]) = (_: Throwable, details: RetryDetails) =>
          logger.info(s"Retrying sending ${binaryHashed.hash.show} to Global L0 after error. Retries so far ${details.retriesSoFar}")

        def performPost() =
          retryingOnFailuresAndAllErrors[Either[NonEmptyList[StateChannelValidationError], Unit]](
            retryPolicy,
            wasSuccessful,
            onFailure(binary),
            onError(binary)
          )(
            lastGlobalSnapshotSigners
              .fold(globalL0ClusterStorage.getRandomPeer) { lastSigners =>
                for {
                  _ <- logger.info(s"Selecting a random peer that participated in the global snapshot consensus")
                  maybeL0Peer <- globalL0ClusterStorage.getRandomPeerExistentOnList(lastSigners.toList)
                  l0Peer <- maybeL0Peer match {
                    case Some(value) => value.pure
                    case None        => globalL0ClusterStorage.getRandomPeer
                  }
                } yield l0Peer
              }
              .flatMap { l0Peer =>
                identifierStorage.get.flatMap { identifier =>
                  stateChannelSnapshotClient
                    .send(identifier, binary.signed)(l0Peer)
                    .onError(e => logger.warn(e)(s"Sending ${binary.hash.show} snapshot to Global L0 peer ${l0Peer.show} failed!"))
                    .flatTap {
                      case Right(_) =>
                        logger.info(s"Sent ${binary.hash.show} to Global L0 peer ${l0Peer.show}") >>
                          stateR.update { state =>
                            val updatedPending = state.tracked.map {
                              case PendingBinary(alreadyTrackedBinary, enqueuedAtOrdinal, sendsSoFar) if alreadyTrackedBinary === binary =>
                                PendingBinary(alreadyTrackedBinary, enqueuedAtOrdinal, NonNegLong.unsafeFrom(sendsSoFar + 1))
                              case tracked => tracked
                            }
                            state.copy(tracked = updatedPending)
                          }

                      case Left(errors) =>
                        logger.error(s"Snapshot ${binary.hash.show} rejected by Global L0 peer ${l0Peer.show}. Reasons: ${errors.show}")
                    }
                }
              }
          ).void

        stateChannelAllowanceLists match {
          case Some(allowanceLists) =>
            for {
              metagraphId <- identifierStorage.get
              result <- allowanceLists.get(metagraphId) match {
                case Some(allowedPeers) =>
                  if (allowedPeers.contains(selfId)) {
                    logger.debug(s"Node $selfId from metagraph $metagraphId allowed to send snapshots") >>
                      performPost()
                  } else {
                    logger.debug(s"Node $selfId from metagraph $metagraphId NOT allowed to send snapshots, skipping!")
                  }
                case None =>
                  if (allowedEmptyAllowanceList.contains(environment)) {
                    logger.debug(s"Empty allowance list, but [$environment] is allowed. Proceeding to send currency snapshot.") >>
                      performPost()
                  } else {
                    logger.debug(s"Empty allowance list and [$environment] is not allowed. Skipping currency snapshot.")
                  }
              }
            } yield result
          case None =>
            if (allowedEmptyAllowanceList.contains(environment)) {
              logger.debug(s"Empty allowance list, trying to send currency snapshot") >>
                performPost()
            } else {
              logger.debug(s"Empty allowance list, but allowance list is required for environment: $environment. Skipping!")
            }
        }
      }
    }
}
