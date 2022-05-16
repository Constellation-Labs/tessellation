package org.tessellation.tools.cli

import java.nio.file.Path

import cats.syntax.contravariantSemigroupal._

import org.tessellation.ext.decline.WithOpts

import com.monovore.decline.Opts
import eu.timepit.refined.auto._

object method {

  sealed trait CliMethod

  case class TestTransactions(
    genesisPath: Path,
    wallets: Int,
    txs: Int,
    ip: String,
    port: Int
  ) extends CliMethod

  object TestTransactions extends WithOpts[TestTransactions] {

    val opts = Opts.subcommand("test-transactions", "Test transactions") {
      (
        Opts.argument[Path](metavar = "genesisPath"),
        Opts.argument[Int](metavar = "wallets"),
        Opts.argument[Int](metavar = "txs"),
        Opts.argument[String](metavar = "ip"),
        Opts.argument[Int](metavar = "port")
      ).mapN(TestTransactions.apply)
    }
  }

  val opts: Opts[CliMethod] =
    TestTransactions.opts
}
