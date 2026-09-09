package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object MembershipVoteGossipRecipientsSuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  test("legacy transport uses current facilitators and removes self") {
    val self = peer(1)
    val current = Set(self, peer(2))
    val frozen = Set(self, peer(2), peer(3))

    expect.same(
      Set(peer(2)),
      MembershipVoteGossipRecipients.select(self, certifiedAtomicMembershipActive = false, current, frozen)
    )
  }

  test("certified atomic transport uses frozen round-start facilitators and removes self") {
    val self = peer(1)
    val current = Set(self, peer(2))
    val frozen = Set(self, peer(2), peer(3))

    expect.same(
      Set(peer(2), peer(3)),
      MembershipVoteGossipRecipients.select(self, certifiedAtomicMembershipActive = true, current, frozen)
    )
  }
}
