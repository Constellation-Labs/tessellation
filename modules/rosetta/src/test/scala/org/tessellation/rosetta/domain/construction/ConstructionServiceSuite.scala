package org.tessellation.rosetta.domain.construction

import cats.data.{EitherT, NonEmptyList}
import cats.effect.{IO, Resource}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.option._

import org.tessellation.dag.transaction.TransactionGenerator
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.domain._
import org.tessellation.rosetta.domain.amount.{Amount, AmountValue, AmountValuePredicate}
import org.tessellation.rosetta.domain.api.construction.ConstructionMetadata.MetadataResult
import org.tessellation.rosetta.domain.api.construction.ConstructionParse
import org.tessellation.rosetta.domain.api.construction.ConstructionPayloads.PayloadsResult
import org.tessellation.rosetta.domain.error._
import org.tessellation.rosetta.domain.generators._
import org.tessellation.rosetta.domain.operation.OperationType.Transfer
import org.tessellation.rosetta.domain.operation.{Operation, OperationIdentifier, OperationIndex}
import org.tessellation.schema.address.Address
import org.tessellation.schema.generators.{addressGen, transactionSaltGen}
import org.tessellation.schema.transaction.{DAGTransaction, _}
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.{Hashed, KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats.{refTypeEq, refTypeOrder, refTypeShow}
import eu.timepit.refined.types.all.PosInt
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object ConstructionServiceSuite extends MutableIOSuite with Checkers with TransactionGenerator {

  type Res = (SecurityProvider[IO], KryoSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar)
      .flatMap(kryo => SecurityProvider.forAsync[IO].map(securityProvider => (securityProvider, kryo)))

  private def getBytes(hashedTransaction: Hashed[DAGTransaction], wantSignedTransaction: Boolean) = if (wantSignedTransaction) {
    JsonBinarySerializer.serialize(hashedTransaction.signed)
  } else {
    JsonBinarySerializer.serialize(hashedTransaction.signed.value)
  }

  def generateTestTransactions(wantSignedTransaction: Boolean)(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) =
    (KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO]).tupled.flatMap {
      case (srcKey, dstKey) =>
        val srcAddress = srcKey.getPublic.toAddress
        val dstAddress = dstKey.getPublic.toAddress
        val txCount = PosInt(100)

        for {
          transactions <- generateTransactions(srcAddress, srcKey, dstAddress, txCount)
          serialized = transactions.map(ht => (getBytes(ht, wantSignedTransaction), ht))
          testValues = serialized.map { case (bytes, ht) => (Hex.fromBytes(bytes), ht.hash, ht) }
        } yield testValues
    }

  def testParseTransactions(
    isSignedTransaction: Boolean,
    testCaseCallbackHandler: (EitherT[F, ConstructionError, ConstructionParse.ParseResult], Hashed[DAGTransaction]) => F[Expectations]
  ) = sharedResource.use { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    generateTestTransactions(wantSignedTransaction = isSignedTransaction)
      .flatMap(_.traverse {
        case (hex, _, transaction) =>
          testCaseCallbackHandler(cs.parseTransaction(hex, isSignedTransaction), transaction)
      })
      .map(_.fold)
  }

  private val constSalt = () => IO.pure(TransactionSalt(0L))

  test("derives public key") { res =>
    implicit val (sp, k) = res

    val publicKey = RosettaPublicKey(
      Hex(
        "0483e4f38072fa59975fc796f220f4c07a7a6a3af1ad7fc091cbd6b8ebe78bac6a959da3587e6e761daf93693d4d2dc6b349fbc44dac5a9fcc5f809a59e93818ea"
      ),
      CurveType.SECP256K1
    )

    val expected = AccountIdentifier(Address("DAG8Q4CnZ1fSMn1Hrui9MmPogEp5UoT5MSH1LwHg"), None)

    val cs = ConstructionService.make[IO](constSalt)

    cs.derive(publicKey).rethrowT.map {
      expect.eql(expected, _)
    }
  }

  test("returns InvalidPublicKey when cannot derive public key") { res =>
    implicit val (sp, k) = res

    val publicKey = RosettaPublicKey(Hex("foobarbaz"), CurveType.SECP256K1)

    val cs = ConstructionService.make[IO](constSalt)

    cs.derive(publicKey).value.map {
      expect.eql(Left(InvalidPublicKey), _)
    }
  }

  test("returns a transaction hash for a valid transaction hex") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    generateTestTransactions(wantSignedTransaction = true)
      .flatMap(_.traverse {
        case (hex, hash, _) =>
          cs.getTransactionIdentifier(hex)
            .rethrowT
            .map(expect.eql(TransactionIdentifier(hash), _))
      })
      .map(_.fold)
  }

  test("returns MalformedTransaction for an invalid transaction hex") { res =>
    implicit val (sp, k) = res

    val hex = Hex(
      "0483e4f38072fa59975fc796f220f4c07a7a6a3af1ad7fc091cbd6b8ebe78bac6a959da3587e6e761daf93693d4d2dc6b349fbc44dac5a9fcc5f809a59e93818ea"
    )

    val cs = ConstructionService.make[IO](constSalt)

    cs.getTransactionIdentifier(hex)
      .value
      .map(
        expect.eql(Left(MalformedTransaction), _)
      )
  }

  test("returns the accountIdentifiers for negative operations") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    forall(addressGen) { address =>
      val operation = Operation(
        OperationIdentifier(OperationIndex(1L)),
        none,
        Transfer,
        none,
        AccountIdentifier(address, none),
        Amount(AmountValue(-1L), currency.Currency(currency.CurrencySymbol("DAG"), currency.CurrencyDecimal(8L)))
      )

      val result = cs
        .getAccountIdentifiers(List(operation))
        .map(_.head.address)

      expect.eql(address.some, result)
    }
  }

  test("returns no accountIdentifiers for non-negative operations") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    forall(addressGen) { address =>
      val operation = Operation(
        OperationIdentifier(OperationIndex(1L)),
        none,
        Transfer,
        none,
        AccountIdentifier(address, none),
        Amount(AmountValue(1L), currency.Currency(currency.CurrencySymbol("DAG"), currency.CurrencyDecimal(8L)))
      )

      val accountIdentifiers = cs.getAccountIdentifiers(List(operation))
      expect.eql(none, accountIdentifiers)
    }
  }

  test("returns operations for a valid signed transaction hex") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    generateTestTransactions(wantSignedTransaction = true)
      .flatMap(_.traverse {
        case (hex, _, _) =>
          cs.parseTransaction(hex, true)
            .rethrowT
            .map(result => expect.eql(Amount(AmountValue(-1L), currency.DAG), result.operations.head.amount))
      })
      .map(_.fold)
  }

  test("returns operations for a valid unsigned transaction hex") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    generateTestTransactions(wantSignedTransaction = false)
      .flatMap(_.traverse {
        case (hex, _, _) =>
          cs.parseTransaction(hex, false)
            .rethrowT
            .map(result => expect.eql(Amount(AmountValue(-1L), currency.DAG), result.operations.head.amount))
      })
      .map(_.fold)
  }

  test("parse signed transaction returns one positive and one negative operation only") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], _: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(2, result.operations.length))

    testParseTransactions(isSignedTransaction = true, testCaseCallbackHandler = testCase)
  }

  test("parse signed transaction returns a negative operation that takes from source address") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], transaction: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(AccountIdentifier(transaction.source, none), result.operations.head.account))

    testParseTransactions(isSignedTransaction = true, testCaseCallbackHandler = testCase)
  }

  test("parse signed transaction returns a negative operation with a negative amount") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], _: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(Amount(AmountValue(-1L), currency.DAG), result.operations.head.amount))

    testParseTransactions(isSignedTransaction = true, testCaseCallbackHandler = testCase)
  }

  test("parse signed transaction returns a positive operation that gives to the destination address") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], transaction: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(AccountIdentifier(transaction.destination, none), result.operations.tail.head.account))

    testParseTransactions(isSignedTransaction = true, testCaseCallbackHandler = testCase)
  }

  test("parse signed transaction returns a positive operation with a positive amount") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], _: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(Amount(AmountValue(1L), currency.DAG), result.operations.tail.head.amount))

    testParseTransactions(isSignedTransaction = true, testCaseCallbackHandler = testCase)
  }

  test("parse unsigned transaction returns one positive and one negative operation only") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], _: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(2, result.operations.length))

    testParseTransactions(isSignedTransaction = false, testCaseCallbackHandler = testCase)
  }

  test("parse unsigned transaction returns a negative operation that takes from source address") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], transaction: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(AccountIdentifier(transaction.source, none), result.operations.head.account))

    testParseTransactions(isSignedTransaction = false, testCaseCallbackHandler = testCase)
  }

  test("parse unsigned transaction returns a negative operation with a negative amount") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], _: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(Amount(AmountValue(-1L), currency.DAG), result.operations.head.amount))

    testParseTransactions(isSignedTransaction = false, testCaseCallbackHandler = testCase)
  }

  test("parse unsigned transaction returns a positive operation that gives to the destination address") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], transaction: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(AccountIdentifier(transaction.destination, none), result.operations.tail.head.account))

    testParseTransactions(isSignedTransaction = false, testCaseCallbackHandler = testCase)
  }

  test("parse unsigned transaction returns a positive operation with a positive amount") {
    val testCase = (parseResult: EitherT[F, ConstructionError, ConstructionParse.ParseResult], _: Hashed[DAGTransaction]) =>
      parseResult.rethrowT
        .map(result => expect.eql(Amount(AmountValue(1L), currency.DAG), result.operations.tail.head.amount))

    testParseTransactions(isSignedTransaction = false, testCaseCallbackHandler = testCase)
  }

  test("getPayloads returns InvalidPayloadOperations when there's no operation with positive amount") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    val gen: Gen[(Operation, MetadataResult)] =
      for {
        (negOp, _) <- payloadOperationsGen
        metadata <- metadataResultGen
      } yield (negOp, metadata)

    forall(gen) {
      case (negOp, metadata) =>
        cs.getPayloads(NonEmptyList.of(negOp, negOp), metadata)
          .value
          .map(expect.eql(Left(NegationPairMismatch), _))
    }
  }

  test("getPayloads returns InvalidPayloadOperations when one operation amount is not negative of the other") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO](constSalt)

    def adjustAmountByOne(amt: Amount): Amount = {
      val newValue = amt.value match {
        case av if av.isPositive =>
          if (av.value === 1L)
            av.value.value + 1
          else
            av.value.value - 1

        case av =>
          if (av.value === -1L)
            av.value.value - 1
          else
            av.value.value + 1
      }
      amt.copy(value = AmountValue(Refined.unsafeApply[Long, AmountValuePredicate](newValue)))
    }

    val gen: Gen[(Operation, Operation, MetadataResult)] =
      for {
        (negOp, posOp) <- payloadOperationsGen.flatMap {
          case (negOp, posOp) =>
            val adjustedNegOp = negOp.copy(amount = adjustAmountByOne(negOp.amount))
            val adjustedPosOp = posOp.copy(amount = adjustAmountByOne(posOp.amount))
            Gen.oneOf(Seq((negOp, adjustedPosOp), (adjustedNegOp, posOp)))
        }
        metadata <- metadataResultGen
      } yield (negOp, posOp, metadata)

    forall(gen) {
      case (negOp, posOp, metadata) =>
        cs.getPayloads(NonEmptyList.of(negOp, posOp), metadata)
          .value
          .map(expect.eql(Left(NegationPairMismatch), _))
    }
  }

  test("getPayloads returns ParseResult on success") { res =>
    implicit val (sp, k) = res

    val gen: Gen[(Operation, Operation, MetadataResult, TransactionSalt)] =
      for {
        (negOp, posOp) <- payloadOperationsGen
        metadata <- metadataResultGen
        salt <- transactionSaltGen
      } yield (negOp, posOp, metadata, salt)

    forall(gen) {
      case (negOp, posOp, metadataResult, salt) =>
        val feeLong = metadataResult.suggestedFee.map(_.value.value.value).getOrElse(0L)
        val dagTransaction =
          DAGTransaction(
            source = negOp.account.address,
            destination = posOp.account.address,
            amount = posOp.amount.value.toTransactionAmount.get,
            fee = TransactionFee(NonNegLong.unsafeFrom(feeLong)),
            parent = metadataResult.metadata.lastReference,
            salt = salt
          )

        val serializedTxn = JsonBinarySerializer.serialize(dagTransaction)
        val txHash = dagTransaction.hash.toOption.get
        val txSignBytes = Hex.fromBytes(txHash.getBytes)

        val expected = PayloadsResult(
          Hex.fromBytes(serializedTxn),
          NonEmptyList.one(SigningPayload(AccountIdentifier(negOp.account.address, none), txSignBytes, SignatureType.ECDSA))
        ).asRight[ConstructionError]

        val cs = ConstructionService.make[IO](() => IO.pure(salt))

        cs.getPayloads(NonEmptyList.of(negOp, posOp), metadataResult)
          .value
          .map(result => expect.eql(expected, result))
    }
  }

}
