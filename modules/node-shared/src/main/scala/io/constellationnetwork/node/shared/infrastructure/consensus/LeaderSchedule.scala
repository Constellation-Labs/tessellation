package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId

/** Deterministic round-robin leader schedule over the frozen round committee.
  *
  * The leader for a round and view depends only on the committee membership, the round index (snapshot ordinal) and the view. It never
  * depends on snapshot hashes, quality scores, or leader-reported observations, so no proposer can steer who leads next. Incrementing the
  * view visits every committee member exactly once per cycle.
  */
object LeaderSchedule {

  def leader(committee: Iterable[PeerId], roundIndex: Long, view: Long): PeerId = {
    val sorted = committee.toVector.distinct.sorted
    require(sorted.nonEmpty, "LeaderSchedule.leader called with an empty committee")
    val n = sorted.size.toLong
    val index = (java.lang.Math.floorMod(roundIndex, n) + java.lang.Math.floorMod(view, n)) % n
    sorted(index.toInt)
  }
}
