package io.constellationnetwork.currency.l0.snapshot.services

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.kernel.Eq
import cats.syntax.all._

import scala.collection.immutable.Queue

import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.all.NonNegLong

@derive(eqv)
sealed trait TrackedBinary

case class PendingBinary(
  binary: Hashed[StateChannelSnapshotBinary],
  currencySnapshotOrdinal: SnapshotOrdinal,
  enqueuedAtOrdinal: SnapshotOrdinal,
  sendsSoFar: NonNegLong
) extends TrackedBinary

case class ConfirmedBinary(
  pendingBinary: PendingBinary,
  confirmationProof: GlobalSnapshotConfirmationProof
) extends TrackedBinary

case class GlobalSnapshotConfirmationProof(
  globalHash: Hash,
  globalOrdinal: SnapshotOrdinal,
  globalEpochProgress: EpochProgress
)

object GlobalSnapshotConfirmationProof {
  def fromGlobalSnapshot(snapshot: Hashed[GlobalIncrementalSnapshot]): GlobalSnapshotConfirmationProof =
    GlobalSnapshotConfirmationProof(snapshot.hash, snapshot.ordinal, snapshot.epochProgress)
}

case class TrackerState(
  tracked: Queue[TrackedBinary],
  cap: NonNegLong,
  retryMode: Boolean,
  noConfirmationsSinceRetryCount: NonNegLong,
  backoffExponent: NonNegLong
)

object TrackerState {
  def empty: TrackerState = TrackerState(
    tracked = Queue.empty[TrackedBinary],
    cap = NonNegLong.unsafeFrom(4L),
    retryMode = false,
    noConfirmationsSinceRetryCount = NonNegLong.MinValue,
    backoffExponent = NonNegLong.MinValue
  )
}

trait BinaryTracker[F[_]] {
  def enqueue(
    binary: Hashed[StateChannelSnapshotBinary],
    currencySnapshotOrdinal: SnapshotOrdinal,
    enqueuedAtGlobal: SnapshotOrdinal
  ): F[Unit]
  def markAsSent(binaryHash: Hash): F[Unit]
  def markAsConfirmed(confirmedHashes: Set[Hash], proof: GlobalSnapshotConfirmationProof): F[Unit]
  def getPendingToRetry(cap: Int): F[List[PendingBinary]]
  def getState: F[TrackerState]
  def updateState(f: TrackerState => TrackerState): F[Unit]
  def clear: F[Unit]
  def pruneConfirmed: F[Unit]
}

object BinaryTracker {
  def make[F[_]: Async]: F[BinaryTracker[F]] =
    Ref.of[F, TrackerState](TrackerState.empty).map { stateRef =>
      new BinaryTracker[F] {
        def enqueue(binary: Hashed[StateChannelSnapshotBinary], currencySnapshotOrdinal: SnapshotOrdinal, enqueuedAt: SnapshotOrdinal)
          : F[Unit] =
          stateRef.update { state =>
            state.copy(tracked = state.tracked :+ PendingBinary(binary, currencySnapshotOrdinal, enqueuedAt, NonNegLong.MinValue))
          }

        def markAsSent(binaryHash: Hash): F[Unit] =
          stateRef.update { state =>
            val updatedTracked = state.tracked.map {
              case PendingBinary(binary, currencySnapshotOrdinal, enqueuedAt, sendsSoFar) if binary.hash === binaryHash =>
                PendingBinary(binary, currencySnapshotOrdinal, enqueuedAt, NonNegLong.unsafeFrom(sendsSoFar.value + 1))
              case other => other
            }
            state.copy(tracked = updatedTracked)
          }

        def markAsConfirmed(confirmedHashes: Set[Hash], proof: GlobalSnapshotConfirmationProof): F[Unit] =
          stateRef.update { state =>
            val indexedTracked = state.tracked.zipWithIndex

            val maybeHighestConfirmationIndex = indexedTracked.collect {
              case (PendingBinary(binaryData, _, _, _), index) if confirmedHashes.contains(binaryData.hash) => index
            }.maxOption

            val updatedTracked = indexedTracked.map {
              case (pendingBinary @ PendingBinary(_, _, _, _), index) if index <= maybeHighestConfirmationIndex.getOrElse(-1) =>
                ConfirmedBinary(pendingBinary, proof)
              case (other, _) => other
            }

            state.copy(tracked = updatedTracked)
          }

        def getPendingToRetry(cap: Int): F[List[PendingBinary]] =
          stateRef.get.map { state =>
            state.tracked.collect { case p: PendingBinary => p }.take(cap).toList
          }

        def getState: F[TrackerState] = stateRef.get

        def updateState(f: TrackerState => TrackerState): F[Unit] = stateRef.update(f)

        def clear: F[Unit] = stateRef.set(TrackerState.empty)

        def pruneConfirmed: F[Unit] =
          stateRef.update { state =>
            val updatedTracked = state.tracked.filterNot(_.isInstanceOf[ConfirmedBinary])
            state.copy(tracked = updatedTracked)
          }
      }
    }
}
