package io.constellationnetwork.currency.validations

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.validations.FeeTransactionValidator.validateAllFeeTransactionsWithSignerPolicy
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}
import org.http4s.{EntityDecoder, EntityEncoder}
import weaver.MutableIOSuite

object FeeTransactionValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] =
    for {
      implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      hasher = Hasher.forJson[IO]
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield (jsonSerializer, hasher, securityProvider)

  @derive(decoder, encoder)
  case class SampleDataUpdate(value: String) extends DataUpdate

  private def dataApplication(implicit jsonSerializer: JsonSerializer[IO]): BaseDataApplicationService[IO] =
    new BaseDataApplicationService[IO] {
      def serializeUpdate(update: DataUpdate): IO[Array[Byte]] = update match {
        case sample: SampleDataUpdate => jsonSerializer.serialize(sample)
        case other                    => IO.raiseError(new IllegalArgumentException(s"Unexpected data update: $other"))
      }

      def serializeState(state: DataOnChainState): IO[Array[Byte]] = ???
      def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, DataOnChainState]] = ???
      def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, DataUpdate]] = ???
      def serializeBlock(block: Signed[DataApplicationBlock]): IO[Array[Byte]] = ???
      def deserializeBlock(bytes: Array[Byte]): IO[Either[Throwable, Signed[DataApplicationBlock]]] = ???
      def serializeCalculatedState(state: DataCalculatedState): IO[Array[Byte]] = ???
      def deserializeCalculatedState(bytes: Array[Byte]): IO[Either[Throwable, DataCalculatedState]] = ???
      def dataEncoder: Encoder[DataUpdate] = ???
      def dataDecoder: Decoder[DataUpdate] = ???
      def signedDataEntityEncoder: EntityEncoder[IO, Signed[DataUpdate]] = ???
      def signedDataEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = ???
      def calculatedStateEncoder: Encoder[DataCalculatedState] = ???
      def calculatedStateDecoder: Decoder[DataCalculatedState] = ???
    }

  private def signedEnvelope(
    coSigned: Boolean
  )(implicit
    jsonSerializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[(Address, DataTransactions)] =
    for {
      sourceKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      coSignerKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      destinationKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = sourceKeyPair.getPublic.toAddress
      update = SampleDataUpdate("activation-matrix")
      signedUpdate <- Signed.forAsyncHasher(update, sourceKeyPair)
      serializedUpdate <- jsonSerializer.serialize(update)
      updateHash <- Hash.fromBytesForSync[IO](serializedUpdate)
      feeTransaction = FeeTransaction(
        source,
        destinationKeyPair.getPublic.toAddress,
        Amount(NonNegLong.unsafeFrom(1L)),
        updateHash
      )
      sourceSigned <- Signed.forAsyncHasher(feeTransaction, sourceKeyPair)
      signedFee <- if (coSigned) sourceSigned.signAlsoWith(coSignerKeyPair) else sourceSigned.pure[IO]
    } yield (source, NonEmptyList[Signed[DataTransaction]](signedUpdate, List(signedFee)))

  private def validate(
    coSigned: Boolean,
    allowSourceAuthorizedCoSigners: Boolean
  )(implicit
    jsonSerializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[ValidatedNec[DataApplicationValidationError, Unit]] =
    for {
      (source, transactions) <- signedEnvelope(coSigned)
      balances = Map(source -> Balance(NonNegLong.unsafeFrom(10L)))
      result <- validateAllFeeTransactionsWithSignerPolicy[IO](
        transactions,
        balances,
        dataApplication,
        allowSourceAuthorizedCoSigners
      )
    } yield result

  private def errorsOf(result: ValidatedNec[DataApplicationValidationError, Unit]): List[DataApplicationValidationError] =
    result.fold(_.toList, _ => List.empty)

  test("the pre-security policy accepts a source-only cryptographically valid fee") { res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    validate(coSigned = false, allowSourceAuthorizedCoSigners = false).map(result => expect(result.isValid))
  }

  test("the pre-security policy rejects a valid additional signer before combining the data update") { res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    validate(coSigned = true, allowSourceAuthorizedCoSigners = false)
      .map(result => expect(errorsOf(result).contains(FeeTransactionNotSignedExclusivelyBySource)))
  }

  test("the security policy accepts a valid additional signer when the source also signs") { res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    validate(coSigned = true, allowSourceAuthorizedCoSigners = true).map(result => expect(result.isValid))
  }
}
