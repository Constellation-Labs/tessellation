package org.tessellation.currency.l0.snapshot.services

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.Queue

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import org.tessellation.node.shared.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security.{Hashed, Hasher}
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import eu.timepit.refined.types.numeric.PosLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._

case class TrackedBinary(
  binary: Hashed[StateChannelSnapshotBinary],
  enqueuedAtOrdinal: SnapshotOrdinal,
  sendsSoFar: NonNegLong
)

case class State(
  pending: Queue[TrackedBinary],
  cap: NonNegLong,
  retryMode: Boolean,
  noConfirmationsSinceRetryCount: NonNegLong,
  backoffExponent: NonNegLong
)

object State {
  def empty: State = State(
    pending = Queue.empty[TrackedBinary],
    cap = 4L,
    retryMode = false,
    noConfirmationsSinceRetryCount = 0L,
    backoffExponent = 0L
  )
}

trait StateChannelBinarySender[F[_]] {
  def processPending: F[Unit]
  def clearPending: F[Unit]
  def confirm(globalSnapshot: GlobalIncrementalSnapshot): F[Unit]
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
              val updatedPending = pending :+ TrackedBinary(binary, enqueuedAtOrdinal = currentOrdinal, 0L)
              val action =
                if (retryMode) logger.warn(s"[RetryMode] Snapshot binary of hash ${binary.hash} enqueued.")
                else
                  post(binary).flatTap(_ => logger.info(s"Snapshot binary of hash ${binary.hash} enqueued and sent to GL0"))
              val newState = state.copy(pending = updatedPending)
              (newState, action)
          }.flatMap(identity)
        }

      def confirm(globalSnapshot: GlobalIncrementalSnapshot): F[Unit] = for {
        identifier <- identifierStorage.get
        binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
        confirmedHashesInGlobalSnapshot <- binaries.traverse(_.toHashed).map(_.map(_.hash)).map(_.toSet)

        _ <- stateR.update { state =>
          val confirmedWithIndex = state.pending.zipWithIndex.filter {
            case (TrackedBinary(tracked, _, _), _) => confirmedHashesInGlobalSnapshot.contains(tracked.hash)
          }
          val maybeConfirmationIndex = confirmedWithIndex.maxByOption { case (_, i) => i }.map { case (_, i) => i }
          val updatedPending = maybeConfirmationIndex.fold(state.pending) { cutAt =>
            state.pending.splitAt(cutAt + 1)._2
          }

          val updatedRetryMode = {
            val hasStalled =
              updatedPending.exists(p => globalSnapshot.ordinal.value - p.enqueuedAtOrdinal.value >= noConfirmationsToTriggerRetryMode)

            if (!state.retryMode) {
              hasStalled
            } else if (state.cap >= state.pending.length && updatedPending.forall(_.sendsSoFar >= 1) && !hasStalled) {
              false
            } else
              state.retryMode
          }

          val (updatedCap, updatedBackoffExponent, updatedNoConfirmationsSinceRetryCount) =
            if ((!updatedRetryMode && state.retryMode) || updatedPending.isEmpty) {
              val empty = State.empty
              val cap = empty.cap
              val backoffExponent = empty.backoffExponent
              val noConfirmationsSinceRetryCount = empty.noConfirmationsSinceRetryCount

              (cap, backoffExponent, noConfirmationsSinceRetryCount)
            } else if (updatedRetryMode) {
              val confirmedCount = maybeConfirmationIndex.map(_ + 1L).getOrElse(0L)

              if (confirmedCount > 0) {
                val cap = {
                  val maxCap = confirmedCount * confirmedCountMultiplier
                  val log2 = (x: Double) => Math.log10(x) / Math.log10(2.0)
                  val surplus = NonNegLong.from {
                    Math.ceil(log2(updatedPending.length.toDouble)).toLong
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
            pending = updatedPending,
            cap = updatedCap,
            retryMode = updatedRetryMode,
            noConfirmationsSinceRetryCount = updatedNoConfirmationsSinceRetryCount,
            backoffExponent = updatedBackoffExponent
          )
        }
      } yield ()

      def processPending: F[Unit] = stateR.get.flatMap { state =>
        if (state.retryMode) {
          val toRetry = state.pending.take(state.cap.toInt)
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
                        val updatedPending = state.pending.map { tracked =>
                          if (tracked.binary === binary) {
                            tracked.copy(sendsSoFar = NonNegLong.unsafeFrom(tracked.sendsSoFar + 1))
                          } else tracked
                        }
                        state.copy(pending = updatedPending)
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
