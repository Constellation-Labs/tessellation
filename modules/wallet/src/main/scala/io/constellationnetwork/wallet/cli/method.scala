package io.constellationnetwork.wallet.cli

import cats.data.NonEmptyList
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.validated._

import io.constellationnetwork.ext.decline.WithOpts
import io.constellationnetwork.ext.decline.decline._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.MessageOrdinal
import io.constellationnetwork.schema.transaction.{TransactionAmount, TransactionFee}

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import fs2.io.file.Path
import io.estatico.newtype.ops._

object method {

  sealed trait CliMethod

  case class ShowAddress() extends CliMethod

  object ShowAddress extends WithOpts[ShowAddress] {

    val opts: Opts[ShowAddress] = Opts.subcommand("show-address", "Shows address") {
      Opts(ShowAddress())
    }
  }

  case class ShowId() extends CliMethod

  object ShowId extends WithOpts[ShowId] {

    val opts: Opts[ShowId] =
      Opts.subcommand("show-id", "Shows address") {
        Opts(ShowId())
      }
  }

  case class ShowPublicKey() extends CliMethod

  object ShowPublicKey extends WithOpts[ShowPublicKey] {

    val opts: Opts[ShowPublicKey] = Opts.subcommand("show-public-key", "Shows public key") {
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
        Opts.option[TransactionFee]("fee", "Transaction fee").withDefault(TransactionFee(NonNegLong(0L))),
        (
          Opts.option[TransactionAmount]("amount", "Transaction DAG amount", "a"),
          Opts.flag("normalized", "Use to mark that amount is already normalized", "n").orFalse
        ).tupled.mapValidated {
          case (amount, normalized) =>
            if (normalized) amount.validNel
            else PosLong.from(amount.coerce * 1e8.toLong).map(_.coerce[TransactionAmount]).toValidatedNel
        },
        Opts.option[Path]("prevTxPath", "Path to previously created transaction file", "p").orNone,
        Opts.option[Path]("nextTxPath", "Path where next transaction should be created", "f")
      ).mapN(CreateTransaction.apply)
    }
  }

  case class CreateTokenLock(
    fee: TransactionFee,
    amount: TransactionAmount,
    parent: Option[Path],
    currencyId: Option[Address],
    unlockEpoch: Option[Long]
  ) extends CliMethod

  object CreateTokenLock extends WithOpts[CreateTokenLock] {

    val opts: Opts[CreateTokenLock] = Opts.subcommand("create-token-lock", "Creates token lock") {
      (
        Opts.option[TransactionFee]("fee", "Token lock fee").withDefault(TransactionFee(NonNegLong(0L))),
        (
          Opts.option[TransactionAmount]("amount", "Amount of DAG to lock", "a"),
          Opts.flag("normalized", "Use to mark that amount is already normalized", "n").orFalse
        ).tupled.mapValidated {
          case (amount, normalized) =>
            if (normalized) amount.validNel
            else PosLong.from(amount.coerce * 1e8.toLong).map(_.coerce[TransactionAmount]).toValidatedNel
        },
        Opts
          .option[Path](
            "parent",
            "Parent tx hash and ordinal in path to load from, " +
              "formatted as API response",
            "p"
          )
          .orNone,
        Opts
          .option[Address](
            "currencyId",
            "Address for metagraph currency id, " +
              "default to none for DAG",
            "c"
          )
          .orNone,
        Opts.option[Long]("unlockEpoch", "Epoch to unlock, defaults to infinite", "u").orNone
      ).mapN(CreateTokenLock.apply)
    }
  }

  case class CreateDelegatedStake(
    nodeId: Option[String],
    fee: TransactionFee,
    amount: TransactionAmount,
    parent: Option[Path],
    tokenLockRef: String
  ) extends CliMethod

  object CreateDelegatedStake extends WithOpts[CreateDelegatedStake] {

    val opts: Opts[CreateDelegatedStake] = Opts.subcommand("create-delegated-stake", "Creates delegated lock") {
      (
        Opts.option[String]("nodeId", "Id of node to stake, defaults to self").orNone,
        Opts.option[TransactionFee]("fee", "Delegated stake fee").withDefault(TransactionFee(NonNegLong(0L))),
        (
          Opts.option[TransactionAmount]("amount", "Amount of DAG to stake, should be same as token lock amount", "a"),
          Opts.flag("normalized", "Use to mark that amount is already normalized", "n").orFalse
        ).tupled.mapValidated {
          case (amount, normalized) =>
            if (normalized) amount.validNel
            else PosLong.from(amount.coerce * 1e8.toLong).map(_.coerce[TransactionAmount]).toValidatedNel
        },
        Opts
          .option[Path](
            "parent",
            "Parent tx hash and ordinal in path to load from, " +
              "formatted as API response",
            "p"
          )
          .orNone,
        Opts.option[String]("token-lock", "Hash reference to active token lock")
      ).mapN(CreateDelegatedStake.apply)
    }
  }

  case class WithdrawDelegatedStake(
    stakeRef: String
  ) extends CliMethod

  object WithdrawDelegatedStake extends WithOpts[WithdrawDelegatedStake] {
    val opts: Opts[WithdrawDelegatedStake] = Opts.subcommand("withdraw-delegated-stake", "Withdraw delegated lock") {
      Opts
        .option[String]("stake-ref", "Hash ref to active delegated stake")
        .map(WithdrawDelegatedStake.apply)
    }
  }
  case class CreateUpdateNodeParameters(
    rewardFraction: Double,
    name: String,
    description: String,
    parent: Option[Path]
  ) extends CliMethod

  object CreateUpdateNodeParameters extends WithOpts[CreateUpdateNodeParameters] {

    val opts: Opts[CreateUpdateNodeParameters] = Opts.subcommand("create-node-params", "Creates node parameters request") {
      (
        Opts
          .option[Double]("reward-fraction", "Percent reward to node from delegated stake")
          .withDefault(0.05),
        Opts.option[String]("name", "Node name").withDefault("node_name"),
        Opts.option[String]("description", "Node name").withDefault("node_description"),
        Opts
          .option[Path](
            "parent",
            "Parent tx hash and ordinal in path to load from, " +
              "formatted as API response",
            "p"
          )
          .orNone
      ).mapN(CreateUpdateNodeParameters.apply)
    }
  }

  case class CreateOwnerSigningMessage(dagAddress: Address, metagraphId: Address, parentOrdinal: MessageOrdinal, outputPath: Option[Path])
      extends CliMethod

  object CreateOwnerSigningMessage extends WithOpts[CreateOwnerSigningMessage] {
    val opts: Opts[CreateOwnerSigningMessage] =
      Opts.subcommand("create-owner-signing-message", "Creates owner signing message") {
        parseMessageOpts.mapN(CreateOwnerSigningMessage.apply)
      }
  }

  case class CreateStakingSigningMessage(dagAddress: Address, metagraphId: Address, parentOrdinal: MessageOrdinal, outputPath: Option[Path])
      extends CliMethod

  object CreateStakingSigningMessage extends WithOpts[CreateStakingSigningMessage] {
    val opts: Opts[CreateStakingSigningMessage] =
      Opts.subcommand("create-staking-signing-message", "Creates staking signing message") {
        parseMessageOpts.mapN(CreateStakingSigningMessage.apply)
      }
  }

  case class MergeSigningMessages(files: NonEmptyList[Path], outputPath: Option[Path]) extends CliMethod

  object MergeSigningMessages extends WithOpts[MergeSigningMessages] {
    val opts: Opts[MergeSigningMessages] = Opts.subcommand("merge-messages", "Merge signing message files") {
      (
        Opts.arguments[Path]("files to merge"),
        Opts.option[Path]("output", "Filename to write output: path must exist and any existing file is overwritten", "f").orNone
      ).mapN(MergeSigningMessages.apply)
    }
  }

  private def parseMessageOpts: (Opts[Address], Opts[Address], Opts[MessageOrdinal], Opts[Option[Path]]) = (
    Opts.option[Address]("address", "DAG Address", "a"),
    Opts.option[Address]("metagraphId", "Metagraph identifier", "m"),
    Opts
      .option[Long]("parentOrdinal", "Ordinal of the parent message", "o")
      .mapValidated(MessageOrdinal(_).toValidatedNel),
    Opts.option[Path]("output", "Filename to write output: path must exist and any existing file is overwritten", "f").orNone
  )

  val opts: Opts[CliMethod] =
    ShowAddress.opts
      .orElse(ShowId.opts)
      .orElse(ShowPublicKey.opts)
      .orElse(CreateTransaction.opts)
      .orElse(CreateOwnerSigningMessage.opts)
      .orElse(CreateStakingSigningMessage.opts)
      .orElse(MergeSigningMessages.opts)
      .orElse(CreateUpdateNodeParameters.opts)
      .orElse(CreateTokenLock.opts)
      .orElse(CreateDelegatedStake.opts)
      .orElse(WithdrawDelegatedStake.opts)
}
