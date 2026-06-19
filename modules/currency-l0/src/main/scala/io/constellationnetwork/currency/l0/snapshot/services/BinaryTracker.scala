package io.constellationnetwork.currency.l0.snapshot.services

import cats.effect.Ref
import cats.effect.kernel.Async
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
  sendsSoFar: NonNegLong,
  // Global ordinal at which this node last *attempted* to post this binary itself.
  // Used to pace re-sends (resend until confirmed, not until merely delivered once).
  lastAttemptAtOrdinal: Option[SnapshotOrdinal]
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
  // Hashes of binaries this node currently has an in-flight self-send for.
  // Guarantees we never fork two concurrent posts of the same binary. Always a subset of pending hashes.
  inFlight: Set[Hash],
  cap: NonNegLong,
  retryMode: Boolean,
  noConfirmationsSinceRetryCount: NonNegLong,
  backoffExponent: NonNegLong
)

object TrackerState {
  def empty: TrackerState = TrackerState(
    tracked = Queue.empty[TrackedBinary],
    inFlight = Set.empty[Hash],
    cap = NonNegLong.unsafeFrom(4L),
    retryMode = false,
    noConfirmationsSinceRetryCount = NonNegLong.MinValue,
    backoffExponent = NonNegLong.MinValue
  )
}

trait BinaryTracker[F[_]] {

  /** Append a binary to the send queue. Non-blocking. Returns false (and does not append) when the queue is at its bound, so a confirmation
    * stall can never grow the queue without limit (OOM-induced restart loops).
    */
  def enqueue(
    binary: Hashed[StateChannelSnapshotBinary],
    currencySnapshotOrdinal: SnapshotOrdinal,
    enqueuedAtGlobal: SnapshotOrdinal
  ): F[Boolean]

  /** Record a successful transport (HTTP 2xx) of a binary. Does NOT mark it confirmed and does NOT suppress re-sending: only a
    * global-snapshot confirmation (markAsConfirmed) stops re-sends.
    */
  def markAsSent(binaryHash: Hash): F[Unit]

  /** Atomically claim the right to post `binaryHash` (de-dup against concurrent / overlapping ticks). Stamps the attempt ordinal when
    * provided. Returns false if already in flight or no longer pending.
    */
  def tryBeginSend(binaryHash: Hash, attemptedAt: Option[SnapshotOrdinal]): F[Boolean]

  /** Release the in-flight claim for `binaryHash` (call from a guarantee so cancellation also releases). */
  def endSend(binaryHash: Hash): F[Unit]

  /** Pending binaries in chain order (FIFO == ascending currency ordinal), capped to `limit`. */
  def getPendingToRetry(cap: Int): F[List[PendingBinary]]

  def getState: F[TrackerState]
  def updateState(f: TrackerState => TrackerState): F[Unit]

  /** Generic atomic read-modify-write, used to apply a full confirmation transition without tearing. */
  def modify[A](f: TrackerState => (TrackerState, A)): F[A]

  def clear: F[Unit]
}

object BinaryTracker {

  /** Pure: mark as confirmed every entry at or before the highest index whose hash is confirmed (the chain invariant guarantees
    * predecessors of a confirmed binary are themselves on-chain), and drop those hashes from the in-flight set.
    */
  def markConfirmedUpToHighest(
    state: TrackerState,
    confirmedHashes: Set[Hash],
    proof: GlobalSnapshotConfirmationProof
  ): TrackerState = {
    val indexedTracked = state.tracked.zipWithIndex

    val maybeHighestConfirmationIndex = indexedTracked.collect {
      case (p: PendingBinary, index) if confirmedHashes.contains(p.binary.hash) => index
    }.maxOption

    val updatedTracked = indexedTracked.map {
      case (pendingBinary: PendingBinary, index) if index <= maybeHighestConfirmationIndex.getOrElse(-1) =>
        ConfirmedBinary(pendingBinary, proof)
      case (other, _) => other
    }

    state.copy(
      tracked = updatedTracked,
      inFlight = state.inFlight.diff(confirmedHashes)
    )
  }

  /** Pure: drop confirmed entries and reconcile in-flight to the surviving pending hashes (prevents leaks). */
  def pruneConfirmed(state: TrackerState): TrackerState = {
    val keptTracked = state.tracked.filterNot(_.isInstanceOf[ConfirmedBinary])
    val pendingHashes = keptTracked.collect { case p: PendingBinary => p.binary.hash }.toSet
    state.copy(tracked = keptTracked, inFlight = state.inFlight.intersect(pendingHashes))
  }

  def make[F[_]: Async](maxTrackedBinaries: Int = 10000): F[BinaryTracker[F]] =
    Ref.of[F, TrackerState](TrackerState.empty).map { stateRef =>
      new BinaryTracker[F] {
        def enqueue(
          binary: Hashed[StateChannelSnapshotBinary],
          currencySnapshotOrdinal: SnapshotOrdinal,
          enqueuedAt: SnapshotOrdinal
        ): F[Boolean] =
          stateRef.modify { state =>
            if (state.tracked.size >= maxTrackedBinaries) (state, false)
            else
              (
                state.copy(
                  tracked = state.tracked :+ PendingBinary(binary, currencySnapshotOrdinal, enqueuedAt, NonNegLong.MinValue, none)
                ),
                true
              )
          }

        def markAsSent(binaryHash: Hash): F[Unit] =
          stateRef.update { state =>
            val updatedTracked = state.tracked.map {
              case p: PendingBinary if p.binary.hash === binaryHash =>
                p.copy(sendsSoFar = NonNegLong.unsafeFrom(p.sendsSoFar.value + 1))
              case other => other
            }
            state.copy(tracked = updatedTracked)
          }

        def tryBeginSend(binaryHash: Hash, attemptedAt: Option[SnapshotOrdinal]): F[Boolean] =
          stateRef.modify { state =>
            val isPending = state.tracked.exists {
              case p: PendingBinary => p.binary.hash === binaryHash
              case _                => false
            }
            if (!isPending || state.inFlight.contains(binaryHash)) (state, false)
            else {
              val updatedTracked = attemptedAt.fold(state.tracked) { ordinal =>
                state.tracked.map {
                  case p: PendingBinary if p.binary.hash === binaryHash => p.copy(lastAttemptAtOrdinal = ordinal.some)
                  case other                                            => other
                }
              }
              (state.copy(inFlight = state.inFlight + binaryHash, tracked = updatedTracked), true)
            }
          }

        def endSend(binaryHash: Hash): F[Unit] =
          stateRef.update(state => state.copy(inFlight = state.inFlight - binaryHash))

        def getPendingToRetry(cap: Int): F[List[PendingBinary]] =
          stateRef.get.map { state =>
            state.tracked.collect { case p: PendingBinary => p }.take(cap).toList
          }

        def getState: F[TrackerState] = stateRef.get

        def updateState(f: TrackerState => TrackerState): F[Unit] = stateRef.update(f)

        def modify[A](f: TrackerState => (TrackerState, A)): F[A] = stateRef.modify(f)

        def clear: F[Unit] = stateRef.set(TrackerState.empty)
      }
    }
}
