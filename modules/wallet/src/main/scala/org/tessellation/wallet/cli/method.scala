package org.tessellation.wallet.cli
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.validated._

import org.tessellation.ext.decline.WithOpts
import org.tessellation.ext.decline.decline._
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{TransactionAmount, TransactionFee}

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegBigInt, PosBigInt}
import fs2.io.file.Path
import io.estatico.newtype.ops._

object method {

  sealed trait CliMethod

  case class ShowAddress() extends CliMethod

  object ShowAddress extends WithOpts[ShowAddress] {

    val opts = Opts.subcommand("show-address", "Shows address") {
      Opts(ShowAddress())
    }
  }

  case class ShowId() extends CliMethod

  object ShowId extends WithOpts[ShowId] {

    val opts =
      Opts.subcommand("show-id", "Shows address") {
        Opts(ShowId())
      }
  }

  case class ShowPublicKey() extends CliMethod

  object ShowPublicKey extends WithOpts[ShowPublicKey] {

    val opts = Opts.subcommand("show-public-key", "Shows public key") {
      Opts(ShowPublicKey())
    }
  }

  case class CreateTransaction(
    destination: Address,
    fee: TransactionFee,
    amount: TransactionAmount,
    prevTxPath: Option[Path],
    nextTxPath: Path
  ) extends CliMethod

  object CreateTransaction extends WithOpts[CreateTransaction] {

    val opts: Opts[CreateTransaction] = Opts.subcommand("create-transaction", "Creates transaction") {
      (
        Opts.option[Address]("destination", "Destination DAG address", "d"),
        Opts.option[TransactionFee]("fee", "Transaction fee").withDefault(TransactionFee(NonNegBigInt(BigInt(0L)))),
        (
          Opts.option[TransactionAmount]("amount", "Transaction DAG amount", "a"),
          Opts.flag("normalized", "Use to mark that amount is already normalized", "n").orFalse
        ).tupled.mapValidated {
          case (amount, normalized) =>
            if (normalized) amount.validNel
            else PosBigInt.from(amount.coerce * 1e8.toLong).map(_.coerce[TransactionAmount]).toValidatedNel
        },
        Opts.option[Path]("prevTxPath", "Path to previously created transaction file", "p").orNone,
        Opts.option[Path]("nextTxPath", "Path where next transaction should be created", "f")
      ).mapN(CreateTransaction.apply)
    }
  }

  val opts: Opts[CliMethod] =
    ShowAddress.opts.orElse(ShowId.opts).orElse(ShowPublicKey.opts).orElse(CreateTransaction.opts)
}
