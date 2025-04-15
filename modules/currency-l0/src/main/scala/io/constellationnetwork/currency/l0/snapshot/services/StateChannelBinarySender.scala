package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.Queue

import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.epoch.EpochProgress
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
  def processPending: F[Unit]
  def clearPending: F[Unit]

  def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit]
  def process(binaryHashed: Hashed[StateChannelSnapshotBinary]): F[Unit]
}

object StateChannelBinarySender {
  def make[F[_]: Async: Hasher](
    identifierStorage: IdentifierStorage[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F]
  ): F[StateChannelBinarySender[F]] =
    Ref
      .of[F, State](State.empty)
      .map(make[F](_, identifierStorage, globalL0ClusterStorage, lastGlobalSnapshotStorage, stateChannelSnapshotClient))

  def make[F[_]: Async: Hasher](
    stateR: Ref[F, State],
    identifierStorage: IdentifierStorage[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F]
  ): StateChannelBinarySender[F] =
    new StateChannelBinarySender[F] {

      private val noConfirmationsToTriggerRetryMode: PosLong = 5L
      private val confirmedCountMultiplier: PosLong = 4L

      private val logger = Slf4jLogger.getLogger

      def process(binary: Hashed[StateChannelSnapshotBinary]): F[Unit] =
        lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue)).flatMap { currentOrdinal =>
          stateR.modify {
            case state @ State(pending, _, retryMode, _, _) =>
              val updatedPending = pending :+ PendingBinary(binary, enqueuedAtOrdinal = currentOrdinal, 0L)
              val action =
                if (retryMode) logger.warn(s"[RetryMode] Snapshot binary of hash ${binary.hash} enqueued.")
                else
                  post(binary).flatTap(_ => logger.info(s"Snapshot binary of hash ${binary.hash} enqueued and sent to GL0"))
              val newState = state.copy(tracked = updatedPending)
              (newState, action)
          }.flatMap(identity)
        }

      def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = for {
        identifier <- identifierStorage.get
        binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
        confirmedHashesInGlobalSnapshot <- binaries.traverse(_.toHashed).map(_.map(_.hash)).map(_.toSet)

        _ <- stateR.update { state =>
          val indexedTracked = state.tracked.zipWithIndex

          val maybeHighestConfirmationIndex = indexedTracked.collect {
            case (PendingBinary(tracked, _, _), index) if confirmedHashesInGlobalSnapshot.contains(tracked.hash) => index
          }.maxOption

          val updatedTracked = indexedTracked.map {
            case (pendingBinary @ PendingBinary(tracked, enqueuedAtOrdinal, sendsSoFar), index)
                if index <= maybeHighestConfirmationIndex.getOrElse(-1) =>
              ConfirmedBinary(pendingBinary, GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot))
            case (other, _) => other
          }

          val updatedRetryMode = {
            val hasStalled =
              updatedTracked.exists {
                case PendingBinary(_, enqueuedAtOrdinal, _) =>
                  globalSnapshot.ordinal.value - enqueuedAtOrdinal.value >= noConfirmationsToTriggerRetryMode
                case _ => false
              }

            if (!state.retryMode) {
              hasStalled
            } else {
              val pendingCount = state.tracked.collect { case _: PendingBinary => 1 }.sum
              val allPendingAlreadySent = updatedTracked.forall {
                case PendingBinary(_, _, sendsSoFar) => sendsSoFar >= 1
                case _                               => true
              }
              if (pendingCount <= state.cap && allPendingAlreadySent && !hasStalled) {
                false
              } else
                state.retryMode
            }
          }

          val (updatedCap, updatedBackoffExponent, updatedNoConfirmationsSinceRetryCount) =
            if ((!updatedRetryMode && state.retryMode) || updatedTracked.isEmpty) {
              val empty = State.empty
              val cap = empty.cap
              val backoffExponent = empty.backoffExponent
              val noConfirmationsSinceRetryCount = empty.noConfirmationsSinceRetryCount

              (cap, backoffExponent, noConfirmationsSinceRetryCount)
            } else if (updatedRetryMode) {
              val confirmedCount = maybeHighestConfirmationIndex.map(_ + 1L).getOrElse(0L)

              if (confirmedCount > 0) {
                val cap = {
                  val maxCap = confirmedCount * confirmedCountMultiplier
                  val log2 = (x: Double) => Math.log10(x) / Math.log10(2.0)
                  val surplus = NonNegLong.from {
                    Math.ceil(log2(updatedTracked.length.toDouble)).toLong
                  }.getOrElse(NonNegLong.MinValue)
                  val proposedCap = state.cap + surplus
                  NonNegLong.from(Math.min(proposedCap, maxCap)).getOrElse(NonNegLong.MinValue)
                }
                val backoffExponent = NonNegLong(0L)
                val noConfirmationsSinceRetryCount = NonNegLong(0L)

                (cap, backoffExponent, noConfirmationsSinceRetryCount)
              } else if (state.cap > 1) {
                val cap = NonNegLong.from(state.cap - 1).getOrElse(NonNegLong.MinValue)
                val backoffExponent = NonNegLong(0L)
                val noConfirmationsSinceRetryCount = NonNegLong(0L)

                (cap, backoffExponent, noConfirmationsSinceRetryCount)
              } else if (state.cap.value == 1) {
                val cap = NonNegLong(0L)
                val backoffExponent = NonNegLong.from(state.backoffExponent + 1L).getOrElse(NonNegLong.MaxValue)
                val noConfirmationsSinceRetryCount = NonNegLong(1L)

                (cap, backoffExponent, noConfirmationsSinceRetryCount)
              } else {
                val noConfirmationsSinceRetryCount =
                  NonNegLong.from(state.noConfirmationsSinceRetryCount + 1).getOrElse(NonNegLong.MaxValue)
                val cap =
                  if (noConfirmationsSinceRetryCount.toLong >= Math.ceil(Math.pow(2.0, state.backoffExponent.toDouble)).toLong)
                    NonNegLong(1L)
                  else state.cap
                val backoffExponent = state.backoffExponent

                (cap, backoffExponent, noConfirmationsSinceRetryCount)
              }
            } else {
              (state.cap, state.backoffExponent, state.noConfirmationsSinceRetryCount)
            }

          State(
            tracked = updatedTracked,
            cap = updatedCap,
            retryMode = updatedRetryMode,
            noConfirmationsSinceRetryCount = updatedNoConfirmationsSinceRetryCount,
            backoffExponent = updatedBackoffExponent
          )
        }
      } yield ()

      def processPending: F[Unit] = stateR.get.flatMap { state =>
        if (state.retryMode) {
          val toRetry = state.tracked.collect { case pendingBinary: PendingBinary => pendingBinary }.take(state.cap.toInt)
          logger.warn(s"[RetryMode] Retrying ${toRetry.size} pending binaries").whenA(toRetry.nonEmpty) >> toRetry.traverse_(tracked =>
            post(tracked.binary)
          )
        } else Applicative[F].unit
      }

      def clearPending: F[Unit] = stateR.set(State.empty)

      private def post(binary: Hashed[StateChannelSnapshotBinary]): F[Unit] = {
        val sendRetries = 5

        val retryPolicy: RetryPolicy[F] = RetryPolicies.limitRetries(sendRetries)

        def wasSuccessful: Either[NonEmptyList[StateChannelValidationError], Unit] => F[Boolean] =
          _.isRight.pure[F]

        def onFailure(binaryHashed: Hashed[StateChannelSnapshotBinary]) =
          (_: Either[NonEmptyList[StateChannelValidationError], Unit], details: RetryDetails) =>
            logger.info(s"Retrying sending ${binaryHashed.hash.show} to Global L0 after rejection. Retries so far ${details.retriesSoFar}")

        def onError(binaryHashed: Hashed[StateChannelSnapshotBinary]) = (_: Throwable, details: RetryDetails) =>
          logger.info(s"Retrying sending ${binaryHashed.hash.show} to Global L0 after error. Retries so far ${details.retriesSoFar}")

        retryingOnFailuresAndAllErrors[Either[NonEmptyList[StateChannelValidationError], Unit]](
          retryPolicy,
          wasSuccessful,
          onFailure(binary),
          onError(binary)
        )(
          globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
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
      }
    }
}
