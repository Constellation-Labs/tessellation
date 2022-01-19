package org.tessellation.dag.transaction

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.{Async, IO}
import cats.syntax.applicative._

import org.tessellation.dag.transaction.TransactionValidator._
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

import eu.timepit.refined.types.numeric.{NonNegBigInt, PosBigInt}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TransactionValidatorSuite extends SimpleIOSuite with Checkers {

  test("should succeed when all values are correct") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, srcKey, _, _, _, tx) = infrastructure
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Valid(signedTx), validationResult)
  }

  test("should succeed when lastTxRef is greater than lastTxRef stored on the node") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, srcKey, _, _, _, baseTx) = infrastructure
      tx = baseTx.copy(parent = TransactionReference(Hash("someHash"), TransactionOrdinal(NonNegBigInt(BigInt(1)))))
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Valid(signedTx), validationResult)
  }

  test("should fail when lastTxRef with greater ordinal has an empty hash") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, srcKey, _, _, _, baseTx) = infrastructure
      tx = baseTx.copy(parent = TransactionReference(Hash(""), TransactionOrdinal(NonNegBigInt(BigInt(1)))))
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(NonZeroOrdinalButEmptyHash(tx))), validationResult)
  }

  test("should fail when lastTxRef's ordinal is lower than one stored on the node") {
    for {
      infrastructure <- prepareTestInfrastructure(
        lastAcceptedTxFn = _ => TransactionReference(Hash("someHash"), TransactionOrdinal(NonNegBigInt(BigInt(1))))
      )
      (txValidator, srcKey, _, _, _, tx) = infrastructure
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(ParentTxRefOrdinalLowerThenStoredLastTxRef(tx))), validationResult)
  }

  test("should fail when lastTxRef's ordinal matches but the hash is different") {
    for {
      infrastructure <- prepareTestInfrastructure(
        lastAcceptedTxFn = _ => TransactionReference(Hash("someHash"), TransactionOrdinal(NonNegBigInt(BigInt(1))))
      )
      (txValidator, srcKey, _, _, _, baseTx) = infrastructure
      tx = baseTx.copy(
        parent = TransactionReference(Hash("someOtherHash"), TransactionOrdinal(NonNegBigInt(BigInt(1))))
      )
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(SameOrdinalButDifferentHashForLastTxRef(tx))), validationResult)
  }

  test("should fail when source address doesn't match signer id") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, _, dstKey, _, _, tx) = infrastructure
      signedTx <- signTx(tx, dstKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(SourceAddressAndSignerIdsDontMatch(tx))), validationResult)
  }

  test("should fail when the signature is wrong") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, srcKey, dstKey, _, _, tx) = infrastructure
      signedTx <- signTx(tx, dstKey).map(
        signed => signed.copy(proofs = signed.proofs.map(_.copy(id = srcKey.getPublic.toId)))
      )
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(InvalidSourceSignature)), validationResult)
  }

  test("should fail when source address doesn't have sufficient balance") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, srcKey, _, srcAddress, _, baseTx) = infrastructure
      tx = baseTx.copy(amount = TransactionAmount(PosBigInt(BigInt(2))))
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(InsufficientSourceBalance(srcAddress))), validationResult)
  }

  test("should fail when source address is the same as destination address") {
    for {
      infrastructure <- prepareTestInfrastructure()
      (txValidator, srcKey, _, srcAddress, _, baseTx) = infrastructure
      tx = baseTx.copy(destination = srcAddress)
      signedTx <- signTx(tx, srcKey)
      validationResult <- txValidator.validate(signedTx)
    } yield expect.same(Invalid(NonEmptyList.one(SourceAndDestinationAddressAreEqual(tx))), validationResult)
  }

  val securityProvider = SecurityProvider.forAsync[IO]
  val kryo = KryoSerializer.forAsync[IO](Map.empty)

  def prepareTestInfrastructure(
    balancesFn: Address => Balance = _ => Balance(NonNegBigInt(BigInt(1))),
    lastAcceptedTxFn: Address => TransactionReference = _ => TransactionReference.empty
  ): IO[(TransactionValidator[IO], KeyPair, KeyPair, Address, Address, Transaction)] =
    securityProvider.use { implicit sc =>
      kryo.use { implicit kp =>
        for {
          txValidator <- new TransactionValidator[IO] {
            override implicit val F: Async[IO] = effect
            override implicit val securityProvider: SecurityProvider[IO] = sc
            override implicit val kryoSerializer: KryoSerializer[IO] = kp

            override def getBalance(address: Address): IO[Balance] =
              balancesFn(address).pure[IO](F)

            override def getLastAcceptedTransactionRef(address: Address): IO[TransactionReference] =
              lastAcceptedTxFn(address).pure[IO](F)
          }.pure[IO]
          srcKey <- KeyPairGenerator.makeKeyPair
          dstKey <- KeyPairGenerator.makeKeyPair
          srcAddress = srcKey.getPublic.toAddress
          dstAddress = dstKey.getPublic.toAddress
          tx = Transaction(
            srcAddress,
            dstAddress,
            TransactionAmount(PosBigInt(BigInt(1))),
            TransactionFee(NonNegBigInt(BigInt(0))),
            TransactionReference.empty,
            TransactionSalt(0L)
          )
        } yield (txValidator, srcKey, dstKey, srcAddress, dstAddress, tx)
      }
    }

  def signTx(tx: Transaction, keyPair: KeyPair): IO[Signed[Transaction]] =
    securityProvider.use { implicit securityProvider =>
      kryo.use { implicit kryoPool =>
        forAsyncKryo(tx, keyPair)
      }
    }
}
