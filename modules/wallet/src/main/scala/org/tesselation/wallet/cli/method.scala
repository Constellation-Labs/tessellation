package org.tesselation.wallet.cli
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.validated._

import org.tesselation.ext.decline.CliMethodOpts
import org.tesselation.ext.decline.decline._
import org.tesselation.schema.address.DAGAddress
import org.tesselation.wallet.cli.method.CreateTransaction.{Amount, Fee}

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import fs2.io.file.Path
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object method {

  sealed trait CliMethod

  case class ShowAddress() extends CliMethod

  object ShowAddress extends CliMethodOpts[ShowAddress] {

    val opts = Opts.subcommand("show-address", "Shows address") {
      Opts(ShowAddress())
    }
  }

  case class ShowId() extends CliMethod

  object ShowId extends CliMethodOpts[ShowId] {

    val opts =
      Opts.subcommand("show-id", "Shows address") {
        Opts(ShowId())
      }
  }

  case class ShowPublicKey() extends CliMethod

  object ShowPublicKey extends CliMethodOpts[ShowPublicKey] {

    val opts = Opts.subcommand("show-public-key", "Shows public key") {
      Opts(ShowPublicKey())
    }
  }

  case class CreateTransaction(
    destination: DAGAddress,
    prevTxPath: Path,
    nextTxPath: Path,
    fee: Fee,
    amount: Amount
  ) extends CliMethod

  object CreateTransaction extends CliMethodOpts[CreateTransaction] {

    @newtype
    case class Fee(value: NonNegLong)

    @newtype
    case class Amount(value: PosLong)

    val opts: Opts[CreateTransaction] = Opts.subcommand("create-transaction", "Creates transaction") {
      (
        Opts.option[DAGAddress]("destination", "Destination DAG address", "d"),
        Opts.option[Path]("prevTxPath", "Path to previously created transaction file", "p"),
        Opts.option[Path]("nextTxPath", "Path where next transaction should be created", "f"),
        Opts.option[Fee]("fee", "Transaction fee").withDefault(Fee(0L)),
        (
          Opts.option[Amount]("amount", "Transaction DAG amount", "a"),
          Opts.flag("normalized", "Use to mark that amount is already normalized", "n").orFalse
        ).tupled.mapValidated {
          case (amount, normalized) =>
            if (normalized) amount.validNel
            else PosLong.from(amount.coerce * 1e8.toLong).map(_.coerce[Amount]).toValidatedNel
        }
      ).mapN(CreateTransaction.apply)
    }
  }

  val opts: Opts[CliMethod] =
    ShowAddress.opts.orElse(ShowId.opts).orElse(ShowPublicKey.opts).orElse(CreateTransaction.opts)
}
