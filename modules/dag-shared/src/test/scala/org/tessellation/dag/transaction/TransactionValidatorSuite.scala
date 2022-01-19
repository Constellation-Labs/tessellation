package org.tessellation.dag.transaction

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.contravariantSemigroupal._

import org.tessellation.dag.transaction.TransactionValidator._
import org.tessellation.ext.cats.effect._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegBigInt, PosBigInt}
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object TransactionValidatorSuite extends ResourceSuite with Checkers {
  override type Res = (
    (Address => Balance, Address => TransactionReference) => TransactionValidator[IO],
    KeyPair,
    KeyPair,
    Address,
    Address,
    Transaction,
    (Transaction, KeyPair) => IO[Signed[Transaction]]
  )

  override def sharedResource: Resource[IO, Res] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Map.empty).flatMap { implicit kp =>
        def txValidator(
          balancesFn: Address => Balance,
          lastAcceptedTxFn: Address => TransactionReference
        ) = new TransactionValidator[IO] {
          val F = effect
          val securityProvider = sp
          val kryoSerializer = kp

          def getBalance(address: Address): IO[Balance] = balancesFn(address).pure[IO]
          def getLastAcceptedTransactionRef(address: Address): IO[TransactionReference] =
            lastAcceptedTxFn(address).pure[IO]
        }

        def signTx(tx: Transaction, keyPair: KeyPair) = forAsyncKryo(tx, keyPair)

        (KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO]).mapN {
          case (srcKey, dstKey) =>
            val src = srcKey.getPublic.toAddress
            val dst = dstKey.getPublic.toAddress

            val tx = Transaction(
              src,
              dst,
              TransactionAmount(PosBigInt(BigInt(1))),
              TransactionFee(NonNegBigInt(BigInt(0))),
              TransactionReference.empty,
              TransactionSalt(0L)
            )

            (srcKey, dstKey, src, dst, tx)
        }.asResource.map {
          case (srcKey, dstKey, src, dst, tx) =>
            (txValidator, srcKey, dstKey, src, dst, tx, signTx)
        }
      }
    }

  val initialBalance: Address => Balance = _ => Balance(NonNegBigInt(BigInt(1)))
  val initialReference: Address => TransactionReference = _ => TransactionReference.empty

  def setReference(hash: Hash) =
    Transaction._ParentHash.replace(hash).andThen(Transaction._ParentOrdinal.replace(TransactionOrdinal(BigInt(1))))

  test("should succeed when all values are correct") {
    case (txValidator, srcKey, _, _, _, tx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      for {
        signedTx <- signTx(tx, srcKey)
        result <- validator.validate(signedTx)
      } yield expect.same(result, Valid(signedTx))
  }

  test("should succeed when lastTxRef is greater than lastTxRef stored on the node") {
    case (txValidator, srcKey, _, _, _, baseTx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      val tx = setReference(Hash("someHash"))(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Valid(signedTx), validationResult)
  }

  test("should fail when lastTxRef with greater ordinal has an empty hash") {
    case (txValidator, srcKey, _, _, _, baseTx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      val tx = setReference(Hash(""))(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(NonZeroOrdinalButEmptyHash(tx))), validationResult)
  }

  test("should fail when lastTxRef's ordinal is lower than one stored on the node") {
    case (txValidator, srcKey, _, _, _, tx, signTx) =>
      val reference =
        (_: Address) => TransactionReference(Hash("someHash"), TransactionOrdinal(NonNegBigInt(BigInt(1))))
      val validator = txValidator(initialBalance, reference)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(ParentTxRefOrdinalLowerThenStoredLastTxRef(tx))), validationResult)
  }

  test("should fail when lastTxRef's ordinal matches but the hash is different") {
    case (txValidator, srcKey, _, _, _, baseTx, signTx) =>
      val reference =
        (_: Address) => TransactionReference(Hash("someHash"), TransactionOrdinal(NonNegBigInt(BigInt(1))))
      val validator = txValidator(initialBalance, reference)

      val tx = setReference(Hash("someOtherHash"))(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(SameOrdinalButDifferentHashForLastTxRef(tx))), validationResult)
  }

  test("should fail when source address doesn't match signer id") {
    case (txValidator, _, dstKey, _, _, tx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      for {
        signedTx <- signTx(tx, dstKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(SourceAddressAndSignerIdsDontMatch(tx))), validationResult)
  }

  test("should fail when the signature is wrong") {
    case (txValidator, srcKey, dstKey, _, _, tx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      for {
        signedTx <- signTx(tx, dstKey).map(
          signed => signed.copy(proofs = signed.proofs.map(_.copy(id = srcKey.getPublic.toId)))
        )
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(InvalidSourceSignature)), validationResult)
  }

  test("should fail when source address doesn't have sufficient balance") {
    case (txValidator, srcKey, _, srcAddress, _, baseTx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      val tx = Transaction._Amount.replace(TransactionAmount(PosBigInt(BigInt(2))))(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(InsufficientSourceBalance(srcAddress))), validationResult)
  }

  test("should fail when source address is the same as destination address") {
    case (txValidator, srcKey, _, srcAddress, _, baseTx, signTx) =>
      val validator = txValidator(initialBalance, initialReference)

      val tx = Transaction._Destination.replace(srcAddress)(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Invalid(NonEmptyList.one(SourceAndDestinationAddressAreEqual(tx))), validationResult)
  }
}
