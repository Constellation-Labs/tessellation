package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ClearingRecord, ClearingState, SnapshotOrdinal}

/** Deterministic membership-clearing ledger (ADR-0036 clearing follow-up).
  *
  * Evidence is the commit QC of round N as embedded in the certified child N+1. When N+1 is derived, every member of round N's frozen
  * committee gains one seated round and, if it signed that embedded commit QC, one present round. Intervals are absolute:
  * `interval(N) = N / intervalLength`. An interval counts for a member only if it was seated for every round of it (`seated ==
  * intervalLength`), so partial intervals after activation, a recovery root or an admission are never counted. A counted interval with
  * `present * 10 < seated` extends the member's low-presence streak; any other counted interval resets it.
  *
  * A member with a streak of at least `requiredLowIntervals` is pending. At most one clearing executes per interval: the pending member with
  * the lowest presence ratio in its last counted interval, then the lowest PeerId. Whether it may leave without a paired replacement is
  * decided by the caller against the contraction floor. Records exist only for seated members.
  */
object MembershipClearing {

  val IntervalLength: Long = 2048L
  val RequiredLowIntervals: Int = 3
  val CooldownOrdinals: Long = 2048L

  final case class Params(intervalLength: Long, requiredLowIntervals: Int)

  val DefaultParams: Params = Params(IntervalLength, RequiredLowIntervals)

  def intervalOf(ordinal: SnapshotOrdinal, params: Params): Long =
    ordinal.value.value / params.intervalLength

  /** Add round `round` to the ledger and close its interval when the deriving round `current` starts a new one.
    *
    * @param round
    *   the round whose commit QC is embedded in the candidate being derived (N)
    * @param committee
    *   round N's frozen committee
    * @param commitSigners
    *   signers of the embedded commit QC for round N
    * @param current
    *   the round being derived (N + 1)
    */
  def recordRound(
    state: ClearingState,
    round: SnapshotOrdinal,
    committee: Set[PeerId],
    commitSigners: Set[PeerId],
    current: SnapshotOrdinal,
    params: Params = DefaultParams
  ): ClearingState = {
    val interval = intervalOf(round, params)

    val updated = committee.toList.map { member =>
      val base = state.members.get(member) match {
        case Some(record) if record.currentInterval < interval => closeInterval(record, params).copy(currentInterval = interval)
        case Some(record)                                     => record
        case None                                             => freshRecord(round, interval)
      }
      member -> base.copy(
        seated = base.seated + 1,
        present = base.present + (if (commitSigners.contains(member)) 1 else 0)
      )
    }

    val recorded = state.copy(members = SortedMap.from(updated))

    if (intervalOf(current, params) > interval) closeAll(recorded, intervalOf(current, params), params) else recorded
  }

  /** The single member to clear in `currentInterval`, if any. */
  def selectTarget(state: ClearingState, currentInterval: Long, params: Params = DefaultParams): Option[PeerId] =
    if (state.clearedInterval.contains(currentInterval)) None
    else
      pending(state, params).sortWith { case ((idA, a), (idB, b)) => lowerPresence(a, b) || (samePresence(a, b) && Ordering[PeerId].lt(idA, idB)) }.headOption
        .map(_._1)

  /** Members whose low-presence streak reached the threshold, in PeerId order. */
  def pending(state: ClearingState, params: Params = DefaultParams): List[(PeerId, ClearingRecord)] =
    state.members.toList.filter { case (_, record) => record.lowStreak >= params.requiredLowIntervals }

  /** Record that `target` was cleared in `interval`: its record is dropped and the interval is marked. */
  def markCleared(state: ClearingState, target: PeerId, interval: Long): ClearingState =
    state.copy(members = state.members - target, clearedInterval = Some(interval))

  /** Drop the records of members that are no longer seated (deterministic exits, evictions, clearing). */
  def retainSeated(state: ClearingState, nextCommittee: Set[PeerId]): ClearingState =
    state.copy(members = state.members.filter { case (member, _) => nextCommittee.contains(member) })

  private def freshRecord(round: SnapshotOrdinal, interval: Long): ClearingRecord =
    ClearingRecord(
      seatActivationOrdinal = round,
      currentInterval = interval,
      seated = 0,
      present = 0,
      lastCountedInterval = None,
      lastCountedSeated = 0,
      lastCountedPresent = 0,
      lowStreak = 0
    )

  private def closeAll(state: ClearingState, nextInterval: Long, params: Params): ClearingState =
    state.copy(members = state.members.map {
      case (member, record) if record.currentInterval < nextInterval =>
        member -> closeInterval(record, params).copy(currentInterval = nextInterval)
      case other => other
    })

  private def closeInterval(record: ClearingRecord, params: Params): ClearingRecord = {
    val counted = record.seated.toLong == params.intervalLength
    val reset = record.copy(seated = 0, present = 0)

    if (!counted) reset
    else
      reset.copy(
        lastCountedInterval = Some(record.currentInterval),
        lastCountedSeated = record.seated,
        lastCountedPresent = record.present,
        lowStreak = if (record.present.toLong * 10L < record.seated.toLong) record.lowStreak + 1 else 0
      )
  }

  // Integer cross-multiplication: a.present / a.seated < b.present / b.seated.
  private def lowerPresence(a: ClearingRecord, b: ClearingRecord): Boolean =
    a.lastCountedPresent.toLong * b.lastCountedSeated.toLong < b.lastCountedPresent.toLong * a.lastCountedSeated.toLong

  private def samePresence(a: ClearingRecord, b: ClearingRecord): Boolean =
    a.lastCountedPresent.toLong * b.lastCountedSeated.toLong == b.lastCountedPresent.toLong * a.lastCountedSeated.toLong
}
