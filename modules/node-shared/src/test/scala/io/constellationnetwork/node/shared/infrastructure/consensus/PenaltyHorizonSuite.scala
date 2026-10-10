package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object PenaltyHorizonSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')

  pureTest("anchors a certified eviction at currentOrdinal plus duration") {
    val next = PenaltyHorizon.next(
      prior = SortedMap.empty,
      certifiedEvictions = Set(a),
      certifiedAdmissions = Set.empty,
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = 100
    )

    expect.same(SortedMap(a -> ord(150L)), next)
  }

  pureTest("clears an entry on certified admission, including same-round eviction") {
    val next = PenaltyHorizon.next(
      prior = SortedMap(a -> ord(200L)),
      certifiedEvictions = Set(b),
      certifiedAdmissions = Set(a, b),
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = 100
    )

    expect.same(SortedMap.empty[PeerId, SnapshotOrdinal], next)
  }

  pureTest("carries unexpired entries unchanged and drops expired ones") {
    val next = PenaltyHorizon.next(
      prior = SortedMap(a -> ord(51L), b -> ord(50L), c -> ord(10L)),
      certifiedEvictions = Set.empty,
      certifiedAdmissions = Set.empty,
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = 100
    )

    // a (until 51 > 50) survives untouched; b (until == current) and c (stale) expire.
    expect.same(SortedMap(a -> ord(51L)), next)
  }

  pureTest("clamps a non-positive duration to zero") {
    val next = PenaltyHorizon.next(
      prior = SortedMap.empty,
      certifiedEvictions = Set(a),
      certifiedAdmissions = Set.empty,
      currentOrdinal = ord(50L),
      penaltyDurationOrdinals = -7
    )

    expect.same(SortedMap(a -> ord(50L)), next)
  }
}
