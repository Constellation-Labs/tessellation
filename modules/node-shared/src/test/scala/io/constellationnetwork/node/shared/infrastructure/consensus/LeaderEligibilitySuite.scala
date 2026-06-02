package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.LeaderEligibility.ExclusionReason
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object LeaderEligibilitySuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')

  private def window(entries: (Long, Set[PeerId])*): SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    SortedMap.from(entries.toList.map { case (o, ps) => ord(o) -> SortedSet.from(ps) })

  private def quality(peers: PeerId*): Map[PeerId, (Int, Int)] =
    peers.map(_ -> (5 -> 5)).toMap

  pureTest("uses graduated Core pool when recent signer window is not deep enough") {
    val result = LeaderEligibility.fromRecentSigners(
      core = List(a, b, c),
      peerQuality = quality(a, b),
      recentSigners = window(1L -> Set(a)),
      minParticipationObservations = 5,
      minLeaderPoolSize = 2
    )

    expect.same(List(a, b), result.leaderPool) &&
    expect.same(1, result.exclusions.count(_.reason == ExclusionReason.NotGraduated)) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("excludes peers absent from all recent signer sets when enough leaders remain") {
    val result = LeaderEligibility.fromRecentSigners(
      core = List(a, b, c, d),
      peerQuality = quality(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      minParticipationObservations = 5,
      minLeaderPoolSize = 2
    )

    expect.same(List(a, b), result.leaderPool) &&
    expect.same(Set(c, d), result.exclusions.collect { case e if e.reason == ExclusionReason.NotRecentSigner => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("bypasses recent signer filter when it would collapse view rotation below the pool floor") {
    val result = LeaderEligibility.fromRecentSigners(
      core = List(a, b, c),
      peerQuality = quality(a, b, c),
      recentSigners = window(
        10L -> Set(a),
        11L -> Set(a),
        12L -> Set(a)
      ),
      minParticipationObservations = 5,
      minLeaderPoolSize = 2
    )

    expect.same(List(a, b, c), result.leaderPool) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("falls back to Core when too few peers are graduated") {
    val result = LeaderEligibility.fromRecentSigners(
      core = List(a, b, c),
      peerQuality = Map(a -> (5 -> 5)),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      minParticipationObservations = 5,
      minLeaderPoolSize = 2
    )

    expect.same(List(a, b, c), result.leaderPool) &&
    expect(result.exclusions.isEmpty)
  }
}
