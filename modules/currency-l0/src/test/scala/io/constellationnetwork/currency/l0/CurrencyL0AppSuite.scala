package io.constellationnetwork.currency.l0

import cats.effect.IO

import io.constellationnetwork.currency.l0.cli.method
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import com.monovore.decline.Command
import weaver.SimpleIOSuite

object CurrencyL0AppSuite extends SimpleIOSuite {

  private val self: PeerId = PeerId(Hex("aa" * 64))

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
}
