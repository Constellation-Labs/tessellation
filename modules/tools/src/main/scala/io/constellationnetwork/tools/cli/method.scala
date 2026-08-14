package io.constellationnetwork.tools.cli

import java.nio.file.Path

import cats.data.NonEmptyList
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.env.env.{KeyAlias, Password, StorePath}
import io.constellationnetwork.ext.decline.WithOpts
import io.constellationnetwork.ext.decline.decline.{coercibleArgument, _}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import com.comcast.ip4s.{Host, Port}
import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument
import eu.timepit.refined.api.RefType.refinedRefType
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.GreaterEqual
import eu.timepit.refined.refineV
import eu.timepit.refined.string.Url
import eu.timepit.refined.types.numeric._

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
    verbose: Boolean,
    fee: NonNegLong
  )

  case class SendTransactionsCmd(
    basicOpts: BasicOpts,
    walletsOpts: WalletsOpts
  ) extends CliMethod

  case class SendStateChannelSnapshotCmd(
    baseUrl: UrlString
  ) extends CliMethod

  case class GetLatestSnapshotInfoCmd(
    networkHost: Host,
    networkPort: Port
  ) extends CliMethod

  case class TxSenderCmd(configPath: String) extends CliMethod

  case class GenerateGl0RecoveryPlanCmd(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    network: String,
    ordinal: NonNegLong,
    snapshotHash: Hash,
    planId: Hash,
    committee: NonEmptyList[PeerId],
    output: Path
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
      Opts.flag("verbose", "Print individual transactions.", "v").map(_ => true).withDefault(false),
      Opts.option[NonNegLong]("fee", "Transaction fee, default 1.", "f").withDefault(NonNegLong(1L))
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
        Opts
          .argument[String](metavar = "baseUrl")
          .map(withProtocol)
          .mapValidated(refineV[Url](_).toValidatedNel)
          .map(SendStateChannelSnapshotCmd.apply)
      }
  }

  object GetLatestSnapshotInfoCmd {
    val opts: Opts[GetLatestSnapshotInfoCmd] =
      Opts.subcommand("get-latest-snapshot-info", "Get latest snapshot-info") {
        (
          Opts.argument[Host](metavar = "host"),
          Opts.argument[Port](metavar = "port")
        ).mapN(GetLatestSnapshotInfoCmd.apply)
      }
  }

  object TxSenderCmd {
    val opts: Opts[TxSenderCmd] =
      Opts.subcommand("tx-sender", "Send transactions from a config file to a public network") {
        Opts
          .option[String]("config", "Path to config file", "c")
          .withDefault("tx-sender.conf")
          .map(TxSenderCmd.apply)
      }
  }

  object GenerateGl0RecoveryPlanCmd {
    val opts: Opts[GenerateGl0RecoveryPlanCmd] =
      Opts.subcommand("generate-gl0-recovery-plan", "Generate one lead-signed, anchor-bound Global L0 recovery plan") {
        (
          StorePath.opts,
          KeyAlias.opts,
          Password.opts,
          Opts.option[String]("network", "Exact network name carried by the configured recovery checkpoint"),
          Opts.option[NonNegLong]("ordinal", "Exact incremental-snapshot anchor ordinal"),
          Opts.option[Hash]("snapshot-hash", "Exact incremental-snapshot anchor hash"),
          Opts.option[Hash]("plan-id", "Operator-selected 64-character hexadecimal incident/plan identifier"),
          Opts.options[PeerId]("committee-peer", "Planned committee PeerId; repeat once per peer (minimum two)"),
          Opts.option[Path]("output", "New JSON output file; an existing file is never overwritten")
        ).mapN(GenerateGl0RecoveryPlanCmd.apply)
      }
  }

  val opts: Opts[CliMethod] =
    SendTransactionsCmd.opts
      .orElse(SendStateChannelSnapshotCmd.opts)
      .orElse(GetLatestSnapshotInfoCmd.opts)
      .orElse(TxSenderCmd.opts)
      .orElse(GenerateGl0RecoveryPlanCmd.opts)

  private val defaultProtocol = "http://"

  private def withProtocol(url: String): String =
    if (url.matches("^[a-z]+://"))
      url
    else
      defaultProtocol + url
}
