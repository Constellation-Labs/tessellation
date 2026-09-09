package io.constellationnetwork.dag.l0.infrastructure.snapshot

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object DelegatedRewardRecipientsSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  pureTest("delegated rewards include every frozen round facilitator in deterministic order") {
    val a = peer('a')
    val b = peer('b')
    val c = peer('c')

    val recipients = GlobalSnapshotConsensusFunctions.delegatedRewardRecipients(Set(c, a, b))

    expect.same(List(a, b, c), recipients)
  }

  pureTest("full-committee reward policy activates inclusively at its replay gate") {
    val activation = SnapshotOrdinal.unsafeApply(100L)

    expect(!GlobalSnapshotConsensusFunctions.usesFullCommitteeRewards(SnapshotOrdinal.unsafeApply(99L), activation)) &&
    expect(GlobalSnapshotConsensusFunctions.usesFullCommitteeRewards(activation, activation))
  }

  pureTest("the frozen reward committee is broad Core plus Tier 1, never Witness") {
    val core = Set(peer('a'), peer('b'))
    val tier1 = Set(peer('c'), peer('d'), peer('e'))
    val witness = peer('f')
    val recipients = GlobalSnapshotConsensusFunctions.delegatedRewardRecipients(core ++ tier1)

    expect.same((core ++ tier1).toList.sorted, recipients) && expect(!recipients.contains(witness))
  }
}
