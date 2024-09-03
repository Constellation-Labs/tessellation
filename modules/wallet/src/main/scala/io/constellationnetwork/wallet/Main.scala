package io.constellationnetwork.wallet

import java.security.KeyPair

import cats.MonadThrow
import cats.data.Validated.{Invalid, Valid}
import cats.data._
import cats.effect.std.Console
import cats.effect.{Async, ExitCode, IO}
import cats.syntax.all._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.transaction.{Transaction, TransactionAmount, TransactionFee}
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.wallet.cli.env.EnvConfig
import io.constellationnetwork.wallet.cli.method._
import io.constellationnetwork.wallet.file.io.{readFromJsonFile, writeToJsonFile}
import io.constellationnetwork.wallet.transaction.createTransaction

import com.monovore.decline._
import com.monovore.decline.effect._
import fs2.io.file.Path
import io.circe.Encoder
import io.circe.syntax._
import io.estatico.newtype.ops._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "",
      header = "Constellation Wallet",
      version = BuildInfo.version
    ) {
  implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    (cli.method.opts, cli.env.opts).mapN {
      case (method, envs) =>
        SecurityProvider.forAsync[IO].use { implicit sp =>
          KryoSerializer.forAsync[IO](sharedKryoRegistrar).use { implicit kryo =>
            JsonSerializer.forSync[IO].asResource.use { implicit jsonSerializer =>
              loadKeyPair[IO](envs).flatMap { keyPair =>
                method match {
                  case ShowAddress() =>
                    toExitCode("Error while showing address.") {
                      showAddress[IO](keyPair)
                    }
                  case ShowId() =>
                    toExitCode("Error while showing id.") {
                      showId[IO](keyPair)
                    }
                  case ShowPublicKey() =>
                    toExitCode("Error while showing public key.") {
                      showPublicKey[IO](keyPair)
                    }
                  case CreateTransaction(destination, fee, amount, prevTxPath, nextTxPath) =>
                    implicit val hasher = Hasher.forKryo[IO]
                    toExitCode("Error while creating transaction.") {
                      createAndStoreTransaction[IO](keyPair, destination, fee, amount, prevTxPath, nextTxPath)
                    }
                  case CreateOwnerSigningMessage(address, metagraphId, parentOrdinal, outputPath) =>
                    implicit val hasher = Hasher.forJson[IO]
                    toExitCode("Error while creating owner signing message.") {
                      createCurrencyMessage[IO](
                        keyPair,
                        MessageType.Owner,
                        address = address,
                        metagraphId = metagraphId,
                        parentOrdinal,
                        outputPath
                      )
                    }
                  case CreateStakingSigningMessage(address, metagraphId, parentOrdinal, outputPath) =>
                    implicit val hasher = Hasher.forJson[IO]
                    toExitCode("Error while creating staking signing message.") {
                      createCurrencyMessage[IO](
                        keyPair,
                        MessageType.Staking,
                        address = address,
                        metagraphId = metagraphId,
                        parentOrdinal,
                        outputPath
                      )
                    }
                  case MergeSigningMessages(files, outputPath: Option[Path]) =>
                    implicit val hasher = Hasher.forJson[IO]
                    toExitCode("Error merging currency messages.") {
                      mergeMessages[IO](files)
                        .flatMap(writeJson[IO, Signed[CurrencyMessage]](outputPath))
                    }
                }
              }
            }
          }
        }
    }

  private def toExitCode(errorMessage: String)(ioa: IO[Unit]): IO[ExitCode] =
    ioa
      .as(ExitCode.Success)
      .handleErrorWith(err => logger.error(err)(errorMessage).as(ExitCode.Error))

  private def showAddress[F[_]: Console](keyPair: KeyPair): F[Unit] =
    Console[F].println(keyPair.getPublic.toAddress)

  private def showId[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic.toId.hex.value)

  private def showPublicKey[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic)

  private def createAndStoreTransaction[F[_]: Async: SecurityProvider: Hasher](
    keyPair: KeyPair,
    destination: Address,
    fee: TransactionFee,
    amount: TransactionAmount,
    prevTxPath: Option[Path],
    nextTxPath: Path
  ): F[Unit] =
    for {
      logger <- Slf4jLogger.create[F]

      prevTx <- prevTxPath match {
        case Some(path) =>
          readFromJsonFile[F, Signed[Transaction]](path)
            .handleErrorWith(e =>
              logger.error(e)(s"Error while reading previous transaction from path $path") >> MonadThrow[F]
                .raiseError[Option[Signed[Transaction]]](e)
            )
        case None => None.pure[F]
      }

      tx <- createTransaction(keyPair, destination, prevTx, fee, amount)

      _ <- writeToJsonFile(nextTxPath)(tx)
    } yield ()

  private def createCurrencyMessage[F[_]: Async: SecurityProvider: Hasher: Console](
    keyPair: KeyPair,
    messageType: MessageType,
    address: Address,
    metagraphId: Address,
    parentOrdinal: MessageOrdinal,
    outputPath: Option[Path]
  ): F[Unit] =
    Signed
      .forAsyncHasher[F, CurrencyMessage](
        CurrencyMessage(messageType, address = address, metagraphId = metagraphId, parentOrdinal),
        keyPair
      )
      .flatMap(writeJson[F, Signed[CurrencyMessage]](outputPath))

  private def loadKeyPair[F[_]: Async: SecurityProvider](cfg: EnvConfig): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        cfg.keystore.toString,
        cfg.keyalias.coerce.value,
        cfg.storepass.coerce.value.toCharArray,
        cfg.keypass.coerce.value.toCharArray
      )

  private def writeJson[F[_]: Async: Console, A: Encoder](outputPath: Option[Path])(a: A): F[Unit] =
    outputPath.fold(Console[F].println(a.asJson.noSpaces))(writeToJsonFile(_)(a))

  private def mergeMessages[F[_]: Async: Hasher: SecurityProvider](files: NonEmptyList[Path]): F[Signed[CurrencyMessage]] = {
    type SignedMessages = NonEmptyList[Signed[CurrencyMessage]]
    type SignedMessagesErrorOr[A] = ValidatedNec[String, A]

    val signedValidator = SignedValidator.make[F]

    def validateMessageValues(inputs: SignedMessages): SignedMessagesErrorOr[SignedMessages] =
      Validated.condNec(
        inputs.toIterable.map(_.value).toSet.size == 1,
        inputs,
        "Messages are not identical"
      )

    def validateProofs(inputs: SignedMessages): F[SignedMessagesErrorOr[SignedMessages]] =
      inputs
        .traverse(input => signedValidator.validateSignatures(input))
        .map(_.reduceLeft(_ *> _))
        .map(_.leftMap(_.map(_.show)).as(inputs))

    files.traverse { p =>
      OptionT(readFromJsonFile[F, Signed[CurrencyMessage]](p))
        .getOrRaise(new Exception(s"Unable to load JSON from $p"))
    }
      .flatMap(inputs => validateProofs(inputs).map(_ *> validateMessageValues(inputs)))
      .flatMap {
        case Valid(inputs)   => Signed(inputs.head.value, inputs.map(_.proofs).reduceLeft(_ ++ _)).pure[F]
        case Invalid(errors) => new Exception(errors.mkString_(",")).raiseError
      }
  }
}
