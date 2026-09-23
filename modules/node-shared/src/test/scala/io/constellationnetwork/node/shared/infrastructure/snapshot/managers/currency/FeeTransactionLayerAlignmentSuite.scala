package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors.FeeTransactionNotSignedExclusivelyBySource
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.validations.FeeTransactionValidator.validateAllFeeTransactionsWithSignerPolicy
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}
import org.http4s.{EntityDecoder, EntityEncoder}
import weaver.MutableIOSuite

/** Final Currency acceptance drops fee transactions it rejects instead of failing the round. That is only safe while the data application
  * layer, which combines the fee's data update first, never accepts a fee transaction that final acceptance would drop.
  */
object FeeTransactionLayerAlignmentSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] =
    for {
      implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      hasher = Hasher.forJson[IO]
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield (jsonSerializer, hasher, securityProvider)

  @derive(decoder, encoder)
  case class SampleDataUpdate(value: String) extends DataUpdate

  sealed trait Signers
  case object SourceOnly extends Signers
  case object SourceAndCoSigner extends Signers
  case object CoSignerOnly extends Signers

  /** Gate combination as seen by both layers for one parent Global ordinal. */
  case class Policy(name: String, feeTransactionSecurityActive: Boolean)

  private val preSecurity = Policy("fee-transaction-security inactive", feeTransactionSecurityActive = false)
  private val security = Policy("fee-transaction-security active", feeTransactionSecurityActive = true)

  private val sourceBalance = Balance(NonNegLong.unsafeFrom(1000L))
  private val feeAmount = Amount(NonNegLong.unsafeFrom(100L))

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

  private case class Envelope(source: Address, fee: Signed[FeeTransaction], transactions: DataTransactions)

  private def envelope(
    signers: Signers
  )(implicit jsonSerializer: JsonSerializer[IO], hasher: Hasher[IO], securityProvider: SecurityProvider[IO]): IO[Envelope] =
    for {
      sourceKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      coSignerKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      destinationKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = sourceKeyPair.getPublic.toAddress
      update = SampleDataUpdate("layer-alignment")
      signedUpdate <- Signed.forAsyncHasher(update, sourceKeyPair)
      updateHash <- jsonSerializer.serialize(update).flatMap(Hash.fromBytesForSync[IO](_))
      feeTransaction = FeeTransaction(source, destinationKeyPair.getPublic.toAddress, feeAmount, updateHash)
      fee <- signers match {
        case SourceOnly        => Signed.forAsyncHasher(feeTransaction, sourceKeyPair)
        case SourceAndCoSigner => Signed.forAsyncHasher(feeTransaction, sourceKeyPair).flatMap(_.signAlsoWith(coSignerKeyPair))
        case CoSignerOnly      => Signed.forAsyncHasher(feeTransaction, coSignerKeyPair)
      }
    } yield Envelope(source, fee, NonEmptyList[Signed[DataTransaction]](signedUpdate, List(fee)))

  private case class Outcome(
    dataApplicationVerdict: ValidatedNec[DataApplicationValidationError, Unit],
    survivors: Option[SortedSet[Signed[FeeTransaction]]],
    sourceBalanceAfter: Option[Balance]
  )

  private def run(policy: Policy, signers: Signers)(
    implicit jsonSerializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[(Envelope, Outcome)] =
    for {
      env <- envelope(signers)
      balances = SortedMap(env.source -> sourceBalance)
      verdict <- validateAllFeeTransactionsWithSignerPolicy[IO](
        env.transactions,
        balances,
        dataApplication,
        allowSourceAuthorizedCoSigners = policy.feeTransactionSecurityActive
      )
      balanceOps = new BalanceOpsManager[IO](FeeTransactionValidator.make[IO](SignedValidator.make[IO]))
      survivors <- balanceOps.validateFeeTxs(
        SortedSet(env.fee).some,
        enforceWalletAuthorization = policy.feeTransactionSecurityActive,
        atOrAboveActivationOrdinal = true
      )
      (after, _) <- balanceOps.acceptFeeTxs(balances, survivors, checkedArithmetic = true)
    } yield (env, Outcome(verdict, survivors, after.get(env.source)))

  private val debited = sourceBalance.minus(feeAmount).toOption

  for {
    policy <- List(preSecurity, security)
    signers <- List(SourceOnly, SourceAndCoSigner, CoSignerOnly)
  } test(s"${policy.name}, $signers: a fee accepted with its data update is also applied by final acceptance") { res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    run(policy, signers).map {
      case (env, outcome) =>
        if (outcome.dataApplicationVerdict.isValid)
          expect(
            outcome.survivors.contains(SortedSet(env.fee)),
            s"final acceptance dropped a fee the data application layer accepted: ${outcome.survivors}"
          ).and(
            expect(
              outcome.sourceBalanceAfter == debited,
              s"source should be debited to $debited, got ${outcome.sourceBalanceAfter}"
            )
          )
        else success
    }
  }

  test("before fee-transaction-security, a co-signed fee is rejected before its data update is combined") { res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    run(preSecurity, SourceAndCoSigner).map {
      case (_, outcome) =>
        expect(
          outcome.dataApplicationVerdict.fold(_.toList, _ => Nil).contains(FeeTransactionNotSignedExclusivelyBySource),
          s"data application verdict: ${outcome.dataApplicationVerdict}"
        )
    }
  }

  test("after fee-transaction-security, a source-authorized co-signed fee is accepted and debited by both layers") { res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    run(security, SourceAndCoSigner).map {
      case (env, outcome) =>
        expect(outcome.dataApplicationVerdict.isValid, s"data application verdict: ${outcome.dataApplicationVerdict}")
          .and(expect(outcome.survivors.contains(SortedSet(env.fee)), s"final acceptance survivors: ${outcome.survivors}"))
          .and(expect(outcome.sourceBalanceAfter == debited, s"source balance after: ${outcome.sourceBalanceAfter}"))
    }
  }
}
