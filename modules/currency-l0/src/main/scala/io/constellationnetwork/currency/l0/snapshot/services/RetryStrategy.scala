package io.constellationnetwork.currency.l0.snapshot.services

import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import eu.timepit.refined.types.numeric.PosLong

object RetryStrategy {
  private val noConfirmationsToTriggerRetryMode: PosLong = PosLong.unsafeFrom(5L)
  private val confirmedCountMultiplier: PosLong = PosLong.unsafeFrom(4L)

  // Hard cap on the exponential backoff. Without it `Math.pow(2, exponent)` saturates to Long.MaxValue, which
  // freezes `cap` at 0 for an unbounded number of snapshots (apparent permanent disconnection). With the clamp the
  // longest silent window between cap-1 send attempts is 2^maxBackoffExponent confirmations.
  private val maxBackoffExponent: NonNegLong = NonNegLong.unsafeFrom(6L)

  def shouldEnterRetryMode(state: TrackerState, currentOrdinal: SnapshotOrdinal): Boolean = {
    val hasStalled = state.tracked.exists {
      case PendingBinary(_, _, enqueuedAtOrdinal, _, _) =>
        // Ignore not-yet-anchored binaries (enqueued before the first global ordinal was known) so we do not
        // trip retry mode spuriously at startup, when enqueuedAtOrdinal defaults to MinValue.
        enqueuedAtOrdinal =!= SnapshotOrdinal.MinValue &&
        currentOrdinal.value - enqueuedAtOrdinal.value >= noConfirmationsToTriggerRetryMode
      case _ => false
    }

    if (!state.retryMode) {
      hasStalled
    } else {
      val pendingCount = state.tracked.collect { case _: PendingBinary => 1 }.sum
      val allPendingAlreadySent = state.tracked.forall {
        case PendingBinary(_, _, _, sendsSoFar, _) => sendsSoFar.value >= 1
        case _                                     => true
      }

      if (pendingCount <= state.cap.value && allPendingAlreadySent && !hasStalled)
        false
      else
        true
    }
  }

  def updateRetryParameters(state: TrackerState, previousRetryMode: Boolean): TrackerState =
    if ((!state.retryMode && previousRetryMode) || state.tracked.isEmpty) {
      TrackerState.empty.copy(tracked = state.tracked, inFlight = state.inFlight)
    } else if (!state.retryMode) {
      state
    } else {
      val confirmedCount = state.tracked.count(_.isInstanceOf[ConfirmedBinary])

      if (confirmedCount > 0) {
        updateCapOnConfirmations(state, confirmedCount)
      } else if (state.cap.value > 1) {
        decreaseCap(state)
      } else if (state.cap.value == 1) {
        enterBackoffMode(state)
      } else {
        updateBackoffCounter(state)
      }
    }

  private def updateCapOnConfirmations(state: TrackerState, confirmedCount: Int): TrackerState = {
    val maxCap = confirmedCount * confirmedCountMultiplier.value
    val log2 = (x: Double) => Math.log10(x) / Math.log10(2.0)
    val surplus = NonNegLong.from {
      Math.ceil(log2(state.tracked.length.toDouble)).toLong
    }.getOrElse(NonNegLong.MinValue)
    val proposedCap = state.cap.value + surplus.value
    val updatedCap = NonNegLong.from(Math.min(proposedCap, maxCap)).getOrElse(NonNegLong.MinValue)

    state.copy(
      cap = updatedCap,
      backoffExponent = NonNegLong.unsafeFrom(0L),
      noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(0L)
    )
  }

  private def decreaseCap(state: TrackerState): TrackerState =
    state.copy(
      cap = NonNegLong.from(state.cap.value - 1).getOrElse(NonNegLong.MinValue),
      backoffExponent = NonNegLong.unsafeFrom(0L),
      noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(0L)
    )

  private def enterBackoffMode(state: TrackerState): TrackerState =
    state.copy(
      cap = NonNegLong.unsafeFrom(0L),
      backoffExponent = NonNegLong.from(Math.min(state.backoffExponent.value + 1L, maxBackoffExponent.value)).getOrElse(maxBackoffExponent),
      noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(1L)
    )

  private def updateBackoffCounter(state: TrackerState): TrackerState = {
    val noConfirmationsSinceRetryCount =
      NonNegLong.from(state.noConfirmationsSinceRetryCount.value + 1).getOrElse(NonNegLong.MaxValue)
    val clampedExponent = Math.min(state.backoffExponent.value, maxBackoffExponent.value)
    val updatedCap =
      if (noConfirmationsSinceRetryCount.value >= Math.ceil(Math.pow(2.0, clampedExponent.toDouble)).toLong)
        NonNegLong.unsafeFrom(1L)
      else state.cap

    state.copy(
      cap = updatedCap,
      noConfirmationsSinceRetryCount = noConfirmationsSinceRetryCount
    )
  }
}
