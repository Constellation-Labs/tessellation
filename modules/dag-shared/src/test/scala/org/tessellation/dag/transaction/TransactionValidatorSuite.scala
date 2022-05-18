package org.tessellation.dag.transaction

import java.security.KeyPair

import cats.data.Validated.Valid
import cats.effect.{IO, Resource}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.validated._

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
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.signature.SignedValidator.InvalidSignatures
import org.tessellation.security.signature.{Signed, SignedValidator}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object TransactionValidatorSuite extends ResourceSuite with Checkers {
  override type Res = (
    TransactionValidator[IO],
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
        val signedValidator = SignedValidator.make
        val txValidator = TransactionValidator.make(signedValidator)

        def signTx(tx: Transaction, keyPair: KeyPair) = forAsyncKryo(tx, keyPair)

        (KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO]).mapN {
          case (srcKey, dstKey) =>
            val src = srcKey.getPublic.toAddress
            val dst = dstKey.getPublic.toAddress

            val tx = Transaction(
              src,
              dst,
              TransactionAmount(PosLong(1L)),
              TransactionFee(NonNegLong(0L)),
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

  val initialBalance: Address => Balance = _ => Balance(NonNegLong(1L))
  val initialReference: Address => TransactionReference = _ => TransactionReference.empty

  def setReference(hash: Hash) =
    Transaction._ParentHash.replace(hash).andThen(Transaction._ParentOrdinal.replace(TransactionOrdinal(1L)))

  test("should succeed when all values are correct") {
    case (txValidator, srcKey, _, _, _, tx, signTx) =>
      for {
        signedTx <- signTx(tx, srcKey)
        result <- txValidator.validate(signedTx)
      } yield expect.same(result, Valid(signedTx))
  }

  test("should fail when source address doesn't match signer id") {
    case (txValidator, _, dstKey, _, _, tx, signTx) =>
      for {
        signedTx <- signTx(tx, dstKey)
        validationResult <- txValidator.validate(signedTx)
      } yield
        expect.same(
          NotSignedBySourceAddressOwner.invalidNec,
          validationResult
        )
  }

  test("should fail when the signature is wrong") {
    case (txValidator, srcKey, dstKey, _, _, tx, signTx) =>
      for {
        signedTx <- signTx(tx, dstKey).map(
          signed => signed.copy(proofs = signed.proofs.map(_.copy(id = srcKey.getPublic.toId)))
        )
        validationResult <- txValidator.validate(signedTx)
      } yield
        expect.same(
          InvalidSigned(InvalidSignatures(signedTx.proofs)).invalidNec,
          validationResult
        )
  }

  test("should fail when source address is the same as destination address") {
    case (txValidator, srcKey, _, srcAddress, _, baseTx, signTx) =>
      val tx = Transaction._Destination.replace(srcAddress)(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- txValidator.validate(signedTx)
      } yield
        expect.same(
          SameSourceAndDestinationAddress(srcAddress).invalidNec,
          validationResult
        )
  }

}
