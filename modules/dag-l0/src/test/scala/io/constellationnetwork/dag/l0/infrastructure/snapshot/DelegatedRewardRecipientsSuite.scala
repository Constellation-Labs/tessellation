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
}
