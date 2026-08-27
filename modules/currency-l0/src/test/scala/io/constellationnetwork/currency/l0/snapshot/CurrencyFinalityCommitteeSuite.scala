package io.constellationnetwork.currency.l0.snapshot

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusOutcome
import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object CurrencyFinalityCommitteeSuite extends SimpleIOSuite {

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)

  private def advance(
    previous: SortedMap[SnapshotOrdinal, Int],
    ordinalValue: Long,
    size: Int
  ): SortedMap[SnapshotOrdinal, Int] =
    CurrencyConsensusOutcome.advanceRecentProofSizes(previous, ordinal(ordinalValue), size, lookbackOrdinals = 10L)

  pureTest("committee high-water mark survives beyond the rolling outage window") {
    val staged = advance(advance(advance(SortedMap.empty, 1L, 1), 3L, 3), 5L, 5)
    val outage = (6L to 30L).foldLeft(staged)((state, value) => advance(state, value, 4))
    val established = CurrencyConsensusOutcome.establishedFinalityCommitteeSize(outage, Some(5))

    expect.all(
      established == 5,
      outage.get(ordinal(5L)).contains(5),
      outage.size <= 12,
      !outage.contains(ordinal(6L))
    )
  }

  pureTest("a recovered full committee moves the marker into the bounded window") {
    val prior = SortedMap(ordinal(5L) -> 5, ordinal(30L) -> 4)
    val recovered = advance(prior, 31L, 5)

    expect.all(recovered.get(ordinal(31L)).contains(5), !recovered.contains(ordinal(5L)))
  }

  pureTest("committee high-water mark is capped by the deterministic configuration") {
    val history = SortedMap(ordinal(9L) -> 9)

    expect.all(
      CurrencyConsensusOutcome.establishedFinalityCommitteeSize(history, Some(5)) == 5,
      CurrencyConsensusOutcome.establishedFinalityCommitteeSize(history, Some(3)) == 3,
      CurrencyConsensusOutcome.establishedFinalityCommitteeSize(history, None) == 0
    )
  }
}
