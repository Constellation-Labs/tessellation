package io.constellationnetwork.currency.validations

import java.security.KeyPair

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object FeeTransactionSignatureValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      json <- JsonSerializer.forSync[IO].asResource
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield (json, securityProvider)

  private def transaction(source: KeyPair, destination: Address, amount: Long = 1L): FeeTransaction =
    FeeTransaction(
      source.getPublic.toAddress,
      destination,
      Amount(NonNegLong.unsafeFrom(amount)),
      Hash.empty
    )

  private def proofFor(value: FeeTransaction, keyPair: KeyPair)(
    implicit jsonSerializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[SignatureProof] =
    FeeTransaction
      .serialize[IO](value)
      .map(Hash.fromBytes)
      .flatMap(SignatureProof.fromHash[IO](keyPair, _))

  private def signed(value: FeeTransaction, keyPairs: NonEmptyList[KeyPair])(
    implicit jsonSerializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Signed[FeeTransaction]] =
    keyPairs
      .traverse(proofFor(value, _))
      .map(proofs => Signed(value, NonEmptySet.fromSetUnsafe(SortedSet.from(proofs.toList))))

  test("accepts an exact signature from the source wallet for any destination") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      signedTransaction <- signed(value, NonEmptyList.one(source))
      result <- validate(signedTransaction)
    } yield expect.same(Valid(signedTransaction), result)
  }

  // Pins the bytes the signature is taken over. FeeTransaction.serialize and the generic Encoder path are not
  // interchangeable, and verifying against the wrong one rejects every valid transaction.
  test("matches the wallet fee transaction signing preimage") { res =>
    implicit val (jsonSerializer, _) = res

    val value = FeeTransaction(
      Address("DAG0KpQNqMsED4FC5grhFCBWG8iwU8Gm6aLhB9w5"),
      Address("DAG0jfGbPHrkX9E1grPgTrSZHVZaYy8gqHeTjbaf"),
      Amount(NonNegLong.unsafeFrom(123L)),
      Hash("0000000000000000000000000000000000000000000000000000000000000000")
    )

    FeeTransaction.serialize[IO](value).map { bytes =>
      expect.all(
        bytes.length === 161,
        Hash.fromBytes(bytes) === Hash("87f637fc6d707d6ce3af508e4938d2b0e5642c210bc210865fe7176db67975a5")
      )
    }
  }

  // The proof id still names the source wallet; only the bytes it was produced over have moved on.
  test("rejects a signature reused after the fee transaction payload changes") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      firstDestination <- KeyPairGenerator.makeKeyPair[IO]
      secondDestination <- KeyPairGenerator.makeKeyPair[IO]
      original = transaction(source, firstDestination.getPublic.toAddress)
      originalSigned <- signed(original, NonEmptyList.one(source))
      modified = Signed(
        original.copy(destination = secondDestination.getPublic.toAddress),
        originalSigned.proofs
      )
      result <- validate(modified)
    } yield
      expect(result match {
        case Invalid(errors) => errors.exists(_.isInstanceOf[InvalidSignatures])
        case Valid(_)        => false
      })
  }

  test("accepts distinct valid co-signers when the source wallet also signs") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      coSigner <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      signedTransaction <- signed(value, NonEmptyList.of(source, coSigner))
      result <- validate(signedTransaction)
    } yield expect.same(Valid(signedTransaction), result)
  }

  test("rejects the entire transaction when an additional proof is invalid") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      coSigner <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      sourceProof <- proofFor(value, source)
      invalidProof <- proofFor(value.copy(amount = Amount(NonNegLong.unsafeFrom(2L))), coSigner)
      signedTransaction = Signed(
        value,
        NonEmptySet.fromSetUnsafe(SortedSet(sourceProof, invalidProof))
      )
      result <- validate(signedTransaction)
    } yield
      expect(result match {
        case Invalid(errors) => errors.exists(_.isInstanceOf[InvalidSignatures])
        case Valid(_)        => false
      })
  }

  test("rejects valid proofs when none belong to the source wallet") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      otherSigner <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      signedTransaction <- signed(value, NonEmptyList.one(otherSigner))
      result <- validate(signedTransaction)
    } yield
      expect(result match {
        case Invalid(errors) => errors.exists(_ === SourceNotSigned)
        case Valid(_)        => false
      })
  }

  test("rejects duplicate signer identities before signature verification") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      validProof <- proofFor(value, source)
      duplicateIdentityProof = validProof.copy(signature = Signature(Hex("00")))
      signedTransaction = Signed(
        value,
        NonEmptySet.fromSetUnsafe(SortedSet(validProof, duplicateIdentityProof))
      )
      result <- validate(signedTransaction)
    } yield
      expect(result match {
        case Invalid(errors) => errors.exists(_.isInstanceOf[DuplicateSigners])
        case Valid(_)        => false
      })
  }

  test("rejects proof sets above the protocol cap before signature verification") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      keyPairs <- List.fill(MaxProofCount.toInt + 1)(()).traverse(_ => KeyPairGenerator.makeKeyPair[IO])
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(keyPairs.head, destination.getPublic.toAddress)
      proofs <- keyPairs.traverse(proofFor(value, _))
      signedTransaction = Signed(
        value,
        NonEmptySet.fromSetUnsafe(SortedSet.from(proofs))
      )
      result <- validate(signedTransaction)
    } yield
      expect(result match {
        case Invalid(errors) => errors.exists(_ === TooManyProofs(MaxProofCount + 1L, MaxProofCount))
        case Valid(_)        => false
      })
  }
}
