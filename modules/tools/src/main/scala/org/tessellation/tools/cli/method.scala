package org.tessellation.tools.cli

import java.nio.file.Path

import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline.coercibleArgument

import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument
import eu.timepit.refined.api.RefType.refinedRefType
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.GreaterEqual
import eu.timepit.refined.refineV
import eu.timepit.refined.string.Url
import eu.timepit.refined.types.numeric.{NonNegInt, PosInt, PosLong}

object method {

  type IntGreaterEqual2 = Int Refined GreaterEqual[2]
  type UrlString = String Refined Url

  sealed trait CliMethod

  case class BasicOpts(
    baseUrl: UrlString,
    take: Option[PosLong],
    chunkSize: PosInt,
    delay: Option[FiniteDuration],
    retryAttempts: NonNegInt,
    verbose: Boolean
  )

  case class SendTransactionsCmd(
    basicOpts: BasicOpts,
    walletsOpts: WalletsOpts
  ) extends CliMethod

  case class SendStateChannelSnapshotCmd(
    baseUrl: UrlString,
    verbose: Boolean
  ) extends CliMethod

  sealed trait WalletsOpts
  case class GeneratedWallets(count: IntGreaterEqual2, genesisPath: Path) extends WalletsOpts
  case class LoadedWallets(walletsPath: Path, alias: String, password: String) extends WalletsOpts

  object SendTransactionsCmd extends WithOpts[SendTransactionsCmd] {
    private val basicOpts = (
      Opts.argument[String](metavar = "baseUrl").map(withProtocol).mapValidated(refineV[Url](_).toValidatedNel),
      Opts.option[PosLong]("take", "Number of transactions. Infinite if unspecified.", "t").orNone,
      Opts.option[PosInt]("chunk", "Size of a chunk, default 1.", "c").withDefault(PosInt(1)),
      Opts.option[FiniteDuration]("delay", "Delay before sending each transaction.", "d").orNone,
      Opts
        .option[NonNegInt]("retryAttempts", "Number of retry attempts to send transaction, default 10.")
        .withDefault(NonNegInt(10)),
      Opts.flag("verbose", "Print individual transactions.", "v").map(_ => true).withDefault(false)
    ).mapN(BasicOpts.apply)

    private val generatedWallets = (
      Opts.option[IntGreaterEqual2]("generateWallets", "Number of wallets to generate, at least 2."),
      Opts.option[Path]("genesisPath", "Specifies where genesis should be stored.")
    ).mapN(GeneratedWallets)

    private val loadedWallets = (
      Opts.option[Path]("loadWallets", "Specifies where wallets (.p12 files) will be loaded from."),
      Opts.option[String]("alias", "Universal alias for all keys, default `alias`.").withDefault("alias"),
      Opts.option[String]("password", "Universal password for all keys, default `password`.").withDefault("password")
    ).mapN(LoadedWallets.apply)

    val opts: Opts[SendTransactionsCmd] = Opts.subcommand("send-transactions", "Send sample transactions") {
      (
        basicOpts,
        generatedWallets.orElse(loadedWallets)
      ).mapN(SendTransactionsCmd.apply)
    }
  }

  object SendStateChannelSnapshotCmd extends WithOpts[SendStateChannelSnapshotCmd] {

    val opts: Opts[SendStateChannelSnapshotCmd] =
      Opts.subcommand("send-state-channel-snapshot", "Send sample state-channel snapshot") {
        (
          Opts.argument[String](metavar = "baseUrl").map(withProtocol).mapValidated(refineV[Url](_).toValidatedNel),
          Opts.flag("verbose", "Display debug messages", "v").map(_ => true).withDefault(false)
        ).mapN(SendStateChannelSnapshotCmd.apply)
      }
  }

  val opts: Opts[CliMethod] = SendTransactionsCmd.opts.orElse(SendStateChannelSnapshotCmd.opts)

  private val defaultProtocol = "http://"

  private def withProtocol(url: String): String =
    if (url.matches("^[a-z]+://"))
      url
    else
      defaultProtocol + url
}
