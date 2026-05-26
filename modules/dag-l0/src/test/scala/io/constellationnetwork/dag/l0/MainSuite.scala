package io.constellationnetwork.dag.l0

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object MainSuite extends SimpleIOSuite {

  private val self: PeerId = PeerId(Hex("aa" * 64))
  private val peerB: PeerId = PeerId(Hex("bb" * 64))
  private val peerC: PeerId = PeerId(Hex("cc" * 64))

  pureTest("rollback bootstrap preserves snapshot proof signers when self signed the checkpoint") {
    val signers = List(peerB, self, peerC)

    expect.same(signers, Main.rollbackBootstrapFacilitators(self, signers))
  }

  pureTest("rollback bootstrap preserves snapshot proof signers when self did not sign the checkpoint") {
    val signers = List(peerB, peerC)

    expect.same(signers, Main.rollbackBootstrapFacilitators(self, signers))
  }

  pureTest("rollback bootstrap falls back to self-only only when checkpoint has no proof signers") {
    val signers = List.empty[PeerId]

    expect.same(List(self), Main.rollbackBootstrapFacilitators(self, signers))
  }
}
