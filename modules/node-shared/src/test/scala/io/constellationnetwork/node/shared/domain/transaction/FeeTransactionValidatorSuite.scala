package io.constellationnetwork.node.shared.domain.transaction

import java.security.KeyPair

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator.InvalidSignatures
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object FeeTransactionValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      json <- JsonSerializer.forAsync[IO].asResource
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield (json, securityProvider)

  private def transaction(source: KeyPair, destination: Address): FeeTransaction =
    FeeTransaction(
      source.getPublic.toAddress,
      destination,
      Amount(NonNegLong.unsafeFrom(1L)),
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

  private def validator(
    implicit jsonSerializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): FeeTransactionValidator[IO] =
    FeeTransactionValidator.make[IO](SignedValidator.make[IO])

  test("preserves legacy identity-only acceptance before the activation gate") { res =>
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
      result <- validator.validate(modified, enforceWalletAuthorization = false)
    } yield expect.same(Valid(modified), result)
  }

  test("rejects the same forged payload at and after the activation gate") { res =>
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
      result <- validator.validate(modified, enforceWalletAuthorization = true)
    } yield
      expect(result match {
        case Invalid(errors) =>
          errors.exists {
            case InvalidSigned(_: InvalidSignatures) => true
            case _                                   => false
          }
        case Valid(_) => false
      })
  }

  test("allows source-authorized co-signers only after the activation gate") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      coSigner <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      signedTransaction <- signed(value, NonEmptyList.of(source, coSigner))
      beforeActivation <- validator.validate(signedTransaction, enforceWalletAuthorization = false)
      afterActivation <- validator.validate(signedTransaction, enforceWalletAuthorization = true)
    } yield
      expect.all(
        beforeActivation match {
          case Invalid(errors) =>
            errors.exists {
              case NotSignedBySourceAddressOwner => true
              case _                             => false
            }
          case Valid(_) => false
        },
        afterActivation == Valid(signedTransaction)
      )
  }

  test("continues to reject transfers to the source address after activation") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, source.getPublic.toAddress)
      signedTransaction <- signed(value, NonEmptyList.one(source))
      result <- validator.validate(signedTransaction, enforceWalletAuthorization = true)
    } yield
      expect(result match {
        case Invalid(errors) =>
          errors.exists {
            case SameSourceAndDestinationAddress(address) => address === value.source
            case _                                        => false
          }
        case Valid(_) => false
      })
  }
}
