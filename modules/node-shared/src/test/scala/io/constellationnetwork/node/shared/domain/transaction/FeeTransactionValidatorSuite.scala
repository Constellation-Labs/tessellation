package io.constellationnetwork.node.shared.domain.transaction

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator.{
  InvalidFeeTransactionSignature,
  NotSignedBySourceAddressOwner,
  SameSourceAndDestinationAddress
}
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object FeeTransactionValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      json <- JsonSerializer.forSync[IO].asResource
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield (json, securityProvider)

  private def validator(implicit j: JsonSerializer[IO], sp: SecurityProvider[IO]): FeeTransactionValidator[IO] =
    FeeTransactionValidator.make[IO](SignedValidator.make[IO])

  private def hashOf(value: FeeTransaction)(implicit j: JsonSerializer[IO]): IO[Hash] =
    FeeTransaction.serialize[IO](value).map(Hash.fromBytes)

  private def signedByAll(value: FeeTransaction, keyPairs: List[KeyPair])(
    implicit j: JsonSerializer[IO],
    sp: SecurityProvider[IO]
  ): IO[Signed[FeeTransaction]] =
    hashOf(value)
      .flatMap(hash => keyPairs.traverse(SignatureProof.fromHash[IO](_, hash)))
      .map(proofs => Signed(value, NonEmptySet.fromSetUnsafe(SortedSet.from(proofs))))

  private def signedBy(value: FeeTransaction, keyPair: KeyPair)(
    implicit j: JsonSerializer[IO],
    sp: SecurityProvider[IO]
  ): IO[Signed[FeeTransaction]] =
    hashOf(value)
      .flatMap(SignatureProof.fromHash[IO](keyPair, _))
      .map(proof => Signed(value, NonEmptySet.one(proof)))

  // A proof naming the source wallet, carrying signature bytes produced by a different key. The address checks
  // read it as the source, so only proof verification separates it from a genuine transaction.
  private def mismatchedProof(value: FeeTransaction, source: KeyPair, other: KeyPair)(
    implicit j: JsonSerializer[IO],
    sp: SecurityProvider[IO]
  ): IO[Signed[FeeTransaction]] =
    for {
      hash <- hashOf(value)
      sourceProof <- SignatureProof.fromHash[IO](source, hash)
      otherProof <- SignatureProof.fromHash[IO](other, hash)
    } yield Signed(value, NonEmptySet.fromSetUnsafe(SortedSet(otherProof.copy(id = sourceProof.id))))

  private def feeTransaction(source: KeyPair, destination: KeyPair): FeeTransaction =
    FeeTransaction(
      source.getPublic.toAddress,
      destination.getPublic.toAddress,
      Amount(NonNegLong(60L)),
      Hash.empty
    )

  // Pins the replay behaviour: below the ordinal the earlier rule runs and this envelope is still accepted.
  test("proof bytes are not checked below the activation ordinal") { res =>
    implicit val (j, sp) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      other <- KeyPairGenerator.makeKeyPair[IO]
      signed <- mismatchedProof(feeTransaction(source, other), source, other)
      result <- validator.validate(signed, verifySignatures = false)
    } yield expect(result.isValid)
  }

  test("proof bytes are checked at or above the activation ordinal") { res =>
    implicit val (j, sp) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      other <- KeyPairGenerator.makeKeyPair[IO]
      signed <- mismatchedProof(feeTransaction(source, other), source, other)
      result <- validator.validate(signed, verifySignatures = true)
    } yield
      expect(result.fold(_.toList, _ => List.empty).exists {
        case _: InvalidFeeTransactionSignature => true
        case _                                 => false
      })
  }

  test("a matching source proof is accepted at or above the activation ordinal") { res =>
    implicit val (j, sp) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      signed <- signedBy(feeTransaction(source, destination), source)
      result <- validator.validate(signed, verifySignatures = true)
    } yield expect(result.isValid)
  }

  test("a self-addressed fee transaction is rejected in both modes") { res =>
    implicit val (j, sp) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      signed <- signedBy(feeTransaction(source, source), source)
      below <- validator.validate(signed, verifySignatures = false)
      atOrAbove <- validator.validate(signed, verifySignatures = true)
      errors = below.fold(_.toList, _ => List.empty) ++ atOrAbove.fold(_.toList, _ => List.empty)
    } yield
      expect(errors.count {
        case _: SameSourceAndDestinationAddress => true
        case _                                  => false
      } === 2)
  }

  // The data application layer rejects a co-signed fee transaction at or above the same ordinal. Acceptance
  // keeps the same rule so the two layers cannot disagree about the same transaction.
  test("a co-signed fee transaction is rejected at or above the activation ordinal") { res =>
    implicit val (j, sp) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      coSigner <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      signed <- signedByAll(feeTransaction(source, destination), List(source, coSigner))
      result <- validator.validate(signed, verifySignatures = true)
    } yield expect(result.fold(_.toList, _ => List.empty).contains(NotSignedBySourceAddressOwner))
  }
}
