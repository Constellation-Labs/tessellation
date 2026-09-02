package io.constellationnetwork.currency.l0

import cats.effect.IO

import io.constellationnetwork.currency.l0.cli.method
import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.monovore.decline.Command
import weaver.SimpleIOSuite

object CurrencyL0AppSuite extends SimpleIOSuite {

  private val self: PeerId = PeerId(Hex("aa" * 64))
  private val checkpoint = ChainTip(SnapshotOrdinal.unsafeApply(10L), Hash("checkpoint"))
  private val snapshot = ChainTip(SnapshotOrdinal.unsafeApply(11L), Hash("snapshot"))

  test("a coordinated Currency rollback always starts from the operator-controlled lead only") {
    IO(expect.same(List(self), CurrencyL0App.rollbackBootstrapFacilitators(self)))
  }

  test("the legacy recovery-refresh option defaults off and requires an explicit flag") {
    val command = Command("currency-l0-test", "test parser")(method.RunRollback.allowSoloConsensusOpts)

    IO(
      expect.same(Some(false), command.parse(Seq.empty).toOption) &&
        expect.same(Some(true), command.parse(Seq("--allow-solo-consensus")).toOption)
    )
  }

  test("local chain tip uses the newer signed snapshot instead of a stale combined checkpoint") {
    IO(expect.same(Some(snapshot), CurrencyL0App.selectLocalChainTip(Some(checkpoint), Some(snapshot))))
  }

  test("local chain tip uses the newer combined checkpoint") {
    IO(expect.same(Some(snapshot), CurrencyL0App.selectLocalChainTip(Some(snapshot), Some(checkpoint))))
  }

  test("local chain tip accepts matching stores and fails closed on an equal-ordinal hash conflict") {
    val matching = ChainTip(checkpoint.ordinal, checkpoint.snapshotHash)
    val conflicting = ChainTip(checkpoint.ordinal, Hash("conflict"))

    IO(
      expect.same(Some(checkpoint), CurrencyL0App.selectLocalChainTip(Some(checkpoint), Some(matching))) &&
        expect.same(None, CurrencyL0App.selectLocalChainTip(Some(checkpoint), Some(conflicting)))
    )
  }
}
