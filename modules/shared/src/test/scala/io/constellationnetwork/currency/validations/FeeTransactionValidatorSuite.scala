package io.constellationnetwork.currency.validations

import cats.data.{NonEmptyList, NonEmptySet, ValidatedNec}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.validations.FeeTransactionValidator.validateAllFeeTransactions
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}
import org.http4s.{EntityDecoder, EntityEncoder}
import weaver.MutableIOSuite

object FeeTransactionValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] =
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
      sp <- SecurityProvider.forAsync[IO]
    } yield (j, h, sp)

  @derive(decoder, encoder)
  case class SampleDataUpdate(value: String) extends DataUpdate

  def mkDataApplication(implicit j: JsonSerializer[IO]): BaseDataApplicationService[IO] =
    new BaseDataApplicationService[IO] {
      def serializeUpdate(update: DataUpdate): IO[Array[Byte]] = update match {
        case sample: SampleDataUpdate => j.serialize(sample)
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

  // One data update plus `amounts.size` fee transactions, all signed by the same source. When
  // refMatchesDataUpdate is false the fee transactions point at a hash no data update in the envelope
  // serializes to, which is what getByDataUpdate silently skips.
  //
  // mismatchedProof pairs the source wallet's proof id with signature bytes produced by a different key. The
  // address checks see the source, so only proof verification separates it from a genuine transaction.
  def mkEnvelope(
    amounts: List[Amount],
    refMatchesDataUpdate: Boolean = true,
    selfAddressed: Boolean = false,
    coSigned: Boolean = false,
    mismatchedProof: Boolean = false
  )(implicit j: JsonSerializer[IO], h: Hasher[IO], sp: SecurityProvider[IO]): IO[(Address, DataTransactions)] =
    for {
      sourceKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = sourceKeyPair.getPublic.toAddress
      otherKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      destination = if (selfAddressed) source else otherKeyPair.getPublic.toAddress
      update = SampleDataUpdate("sample")
      signedUpdate <- Signed.forAsyncHasher(update, sourceKeyPair)
      serializedUpdate <- j.serialize(update)
      dataUpdateRef = if (refMatchesDataUpdate) Hash.fromBytes(serializedUpdate) else Hash.empty
      feeTransactions <- amounts.traverse { amount =>
        val feeTransaction = FeeTransaction(source, destination, amount, dataUpdateRef)

        if (mismatchedProof)
          for {
            hash <- FeeTransaction.serialize[IO](feeTransaction).map(Hash.fromBytes)
            sourceProof <- SignatureProof.fromHash[IO](sourceKeyPair, hash)
            otherProof <- SignatureProof.fromHash[IO](otherKeyPair, hash)
          } yield Signed(feeTransaction, NonEmptySet.one(otherProof.copy(id = sourceProof.id)))
        else
          Signed
            .forAsyncHasher(feeTransaction, sourceKeyPair)
            .flatMap(signed => if (coSigned) signed.signAlsoWith(otherKeyPair) else signed.pure[IO])
      }
    } yield (source, NonEmptyList[Signed[DataTransaction]](signedUpdate, feeTransactions))

  def errorsOf(result: ValidatedNec[DataApplicationValidationError, Unit]): List[DataApplicationValidationError] =
    result.fold(_.toList, _ => List.empty)

  // The amount of each of the four fee transactions minted on mainnet at metagraph ordinal 731261. Their
  // total is exactly 2^64, so an unchecked sum wraps to 0 and clears any balance check.
  val exploitAmount: Amount = Amount(NonNegLong(4611686018427387904L))

  test("a fee transaction referencing no data update in the envelope is rejected") { res =>
    implicit val (j, h, sp) = res

    for {
      (source, dataTransactions) <- mkEnvelope(List(Amount(NonNegLong(60L))), refMatchesDataUpdate = false)
      balances = Map(source -> Balance(NonNegLong(100L)))
      result <- validateAllFeeTransactions[IO](dataTransactions, balances, mkDataApplication)
    } yield expect(errorsOf(result).contains(MissingDataUpdateOfFeeTransaction))
  }

  test("fee transactions from one source whose total overflows Long are rejected rather than wrapping") { res =>
    implicit val (j, h, sp) = res

    for {
      (_, dataTransactions) <- mkEnvelope(List.fill(4)(exploitAmount), refMatchesDataUpdate = true)
      result <- validateAllFeeTransactions[IO](dataTransactions, Map.empty[Address, Balance], mkDataApplication)
    } yield expect(errorsOf(result).contains(SourceWalletNotEnoughBalance))
  }

  test("fee transactions that each fit the source balance but jointly exceed it are rejected") { res =>
    implicit val (j, h, sp) = res

    val affordableAlone = Amount(NonNegLong(60L))

    for {
      (source, dataTransactions) <- mkEnvelope(List(affordableAlone, affordableAlone), refMatchesDataUpdate = true)
      balances = Map(source -> Balance(NonNegLong(100L)))
      result <- validateAllFeeTransactions[IO](dataTransactions, balances, mkDataApplication)
    } yield expect(errorsOf(result).contains(SourceWalletNotEnoughBalance))
  }

  test("a funded fee transaction referencing a data update in the envelope is valid") { res =>
    implicit val (j, h, sp) = res

    for {
      (source, dataTransactions) <- mkEnvelope(List(Amount(NonNegLong(60L))), refMatchesDataUpdate = true)
      balances = Map(source -> Balance(NonNegLong(100L)))
      result <- validateAllFeeTransactions[IO](dataTransactions, balances, mkDataApplication)
    } yield expect(result.isValid)
  }

  // The two below are rejected by node-shared's FeeTransactionValidator at acceptance. If this layer lets
  // them through, the block is accepted, combine applies the data update, and the fee is dropped at
  // acceptance -- the update happens for free.
  test("a fee transaction sending to its own source is rejected") { res =>
    implicit val (j, h, sp) = res

    for {
      (source, dataTransactions) <- mkEnvelope(List(Amount(NonNegLong(60L))), selfAddressed = true)
      balances = Map(source -> Balance(NonNegLong(100L)))
      result <- validateAllFeeTransactions[IO](dataTransactions, balances, mkDataApplication)
    } yield expect(errorsOf(result).contains(SameSourceAndDestinationAddress))
  }

  test("a fee transaction carrying a signature other than the source's is rejected") { res =>
    implicit val (j, h, sp) = res

    for {
      (source, dataTransactions) <- mkEnvelope(List(Amount(NonNegLong(60L))), coSigned = true)
      balances = Map(source -> Balance(NonNegLong(100L)))
      result <- validateAllFeeTransactions[IO](dataTransactions, balances, mkDataApplication)
    } yield expect(errorsOf(result).contains(FeeTransactionNotSignedExclusivelyBySource))
  }

  // The address checks alone accept this envelope, so the proof check is what decides it.
  test("a fee transaction whose proof does not verify against the transaction is rejected") { res =>
    implicit val (j, h, sp) = res

    for {
      (source, dataTransactions) <- mkEnvelope(List(Amount(NonNegLong(60L))), mismatchedProof = true)
      balances = Map(source -> Balance(NonNegLong(100L)))
      result <- validateAllFeeTransactions[IO](dataTransactions, balances, mkDataApplication)
    } yield expect(errorsOf(result).contains(InvalidSignature))
  }
}
