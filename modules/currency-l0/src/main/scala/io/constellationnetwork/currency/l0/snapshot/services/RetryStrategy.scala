package io.constellationnetwork.currency.l0.snapshot.services

import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import eu.timepit.refined.types.numeric.PosLong

object RetryStrategy {
  private val noConfirmationsToTriggerRetryMode: PosLong = PosLong.unsafeFrom(5L)
  private val confirmedCountMultiplier: PosLong = PosLong.unsafeFrom(4L)

  // The send budget (`cap`) is NEVER allowed to reach 0. An external health monitor restarts the metagraph if the
  // global network sees no metagraph snapshot for ~5 minutes; going silent would therefore GUARANTEE that restart.
  // Instead we always keep posting at least the chain head every tick, so a recoverable stall heals before the
  // restart window, and only a genuinely unrecoverable stall is left for the (clean, rollback-based) restart.
  private val minCap: NonNegLong = NonNegLong.unsafeFrom(1L)

  // Upper bound on the informational stall counter (how many confirmation-less ordinals we have seen at cap == 1).
  // Purely for metrics; it no longer gates sending. Clamped so it cannot overflow.
  private val maxStallExponent: NonNegLong = NonNegLong.unsafeFrom(6L)

  def shouldEnterRetryMode(state: TrackerState, currentOrdinal: SnapshotOrdinal): Boolean = {
    val hasStalled = state.tracked.exists {
      case PendingBinary(_, _, enqueuedAtOrdinal, _, _) =>
        // Ignore not-yet-anchored binaries (enqueued before the first global ordinal was known) so we do not trip
        // retry mode spuriously at startup, when enqueuedAtOrdinal defaults to MinValue.
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

      if (confirmedCount > 0)
        updateCapOnConfirmations(state, confirmedCount)
      else if (state.cap.value > minCap.value)
        decreaseCap(state)
      else
        holdAtMinimum(state)
    }

  private def updateCapOnConfirmations(state: TrackerState, confirmedCount: Int): TrackerState = {
    val maxCap = confirmedCount * confirmedCountMultiplier.value
    val log2 = (x: Double) => Math.log10(x) / Math.log10(2.0)
    val surplus = NonNegLong.from {
      Math.ceil(log2(state.tracked.length.toDouble)).toLong
    }.getOrElse(NonNegLong.MinValue)
    val proposedCap = state.cap.value + surplus.value
    val updatedCap = NonNegLong.from(Math.max(Math.min(proposedCap, maxCap), minCap.value)).getOrElse(minCap)

    state.copy(
      cap = updatedCap,
      backoffExponent = NonNegLong.unsafeFrom(0L),
      noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(0L)
    )
  }

  // Shrink the per-tick send budget on a confirmation-less ordinal, but never below `minCap` (== 1).
  private def decreaseCap(state: TrackerState): TrackerState =
    state.copy(
      cap = NonNegLong.from(Math.max(state.cap.value - 1, minCap.value)).getOrElse(minCap),
      backoffExponent = NonNegLong.unsafeFrom(0L),
      noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(0L)
    )

  // Already at the minimum budget and still no confirmations: KEEP sending the head every tick (never go silent),
  // only advancing informational stall counters.
  private def holdAtMinimum(state: TrackerState): TrackerState =
    state.copy(
      cap = minCap,
      noConfirmationsSinceRetryCount = NonNegLong.from(state.noConfirmationsSinceRetryCount.value + 1).getOrElse(NonNegLong.MaxValue),
      backoffExponent = NonNegLong.from(Math.min(state.backoffExponent.value + 1L, maxStallExponent.value)).getOrElse(maxStallExponent)
    )
}
