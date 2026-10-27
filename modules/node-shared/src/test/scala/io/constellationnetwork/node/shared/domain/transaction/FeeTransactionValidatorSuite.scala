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
      result <- validator.validate(modified, FeeTransactionSignerPolicy.LegacyExclusiveSource)
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
      result <- validator.validate(modified, FeeTransactionSignerPolicy.VerifiedSourceAuthorized)
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
      beforeActivation <- validator.validate(signedTransaction, FeeTransactionSignerPolicy.LegacyExclusiveSource)
      afterActivation <- validator.validate(signedTransaction, FeeTransactionSignerPolicy.VerifiedSourceAuthorized)
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
      result <- validator.validate(signedTransaction, FeeTransactionSignerPolicy.VerifiedSourceAuthorized)
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

  // Release/mainnet #1577 (58e10642a): from fixing-data-application-fee-validation until fee-transaction-security
  // acceptance verifies every proof and then requires every proof to belong to the source wallet.
  private val verifiedExclusive = FeeTransactionSignerPolicy.VerifiedExclusiveSource

  // A proof naming the source wallet that carries signature bytes produced by a different key. The address checks
  // read it as the source, so only proof verification separates it from a genuine transaction.
  private def mismatchedProof(value: FeeTransaction, source: KeyPair, other: KeyPair)(
    implicit jsonSerializer: JsonSerializer[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Signed[FeeTransaction]] =
    for {
      sourceProof <- proofFor(value, source)
      otherProof <- proofFor(value, other)
    } yield Signed(value, NonEmptySet.one(otherProof.copy(id = sourceProof.id)))

  private def errorsOf(result: FeeTransactionValidationErrorOr[Signed[FeeTransaction]]): List[FeeTransactionValidationError] =
    result.fold(_.toList, _ => List.empty)

  test("verified-exclusive policy: proof bytes are not checked by the legacy rule but are checked from the boundary") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      other <- KeyPairGenerator.makeKeyPair[IO]
      forged <- mismatchedProof(transaction(source, other.getPublic.toAddress), source, other)
      legacy <- validator.validate(forged, FeeTransactionSignerPolicy.LegacyExclusiveSource)
      verified <- validator.validate(forged, verifiedExclusive)
    } yield
      expect(legacy.isValid, s"below the boundary the forged proof replays as accepted: $legacy").and(
        expect(
          errorsOf(verified).exists {
            case InvalidSigned(_: InvalidSignatures) => true
            case _                                   => false
          },
          s"from the boundary the forged proof is rejected by proof verification: $verified"
        )
      )
  }

  test("verified-exclusive policy accepts a matching source proof") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      signedTransaction <- signed(transaction(source, destination.getPublic.toAddress), NonEmptyList.one(source))
      result <- validator.validate(signedTransaction, verifiedExclusive)
    } yield expect(result == Valid(signedTransaction), s"a matching source proof is valid: $result")
  }

  test("a self-addressed fee transaction is rejected under every signer policy") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      signedTransaction <- signed(transaction(source, source.getPublic.toAddress), NonEmptyList.one(source))
      results <- List(
        FeeTransactionSignerPolicy.LegacyExclusiveSource,
        verifiedExclusive,
        FeeTransactionSignerPolicy.VerifiedSourceAuthorized
      ).traverse(policy => validator.validate(signedTransaction, policy).tupleLeft(policy))
    } yield
      results.foldMap {
        case (policy, result) =>
          expect(errorsOf(result).contains(SameSourceAndDestinationAddress(signedTransaction.source)), s"$policy: $result")
      }
  }

  test("verified-exclusive policy rejects a valid co-signer, as the data application layer does") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      coSigner <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      signedTransaction <- signed(transaction(source, destination.getPublic.toAddress), NonEmptyList.of(source, coSigner))
      result <- validator.validate(signedTransaction, verifiedExclusive)
    } yield expect(errorsOf(result).contains(NotSignedBySourceAddressOwner), s"co-signed transaction: $result")
  }

  test("verified-exclusive policy rejects, rather than raises on, a proof id that is not a public key") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      genuine <- signed(transaction(source, destination.getPublic.toAddress), NonEmptyList.one(source))
      bogusId = io.constellationnetwork.schema.ID.Id(io.constellationnetwork.security.hex.Hex("00" * 64))
      malformed = Signed(genuine.value, NonEmptySet.one(genuine.proofs.head.copy(id = bogusId)))
      result <- validator.validate(malformed, verifiedExclusive).attempt
    } yield
      expect(
        result.exists(_.isInvalid),
        s"an unparseable proof id is an invalid transaction (dropped), never an error that fails the snapshot: $result"
      )
  }

  test("verified-exclusive policy caps the number of proofs even when every proof names the source") { res =>
    implicit val (jsonSerializer, securityProvider) = res

    for {
      source <- KeyPairGenerator.makeKeyPair[IO]
      destination <- KeyPairGenerator.makeKeyPair[IO]
      value = transaction(source, destination.getPublic.toAddress)
      proofs <- List.fill(17)(proofFor(value, source)).sequence
      many = Signed(value, NonEmptySet.fromSetUnsafe(SortedSet.from(proofs)))
      legacy <- validator.validate(many, FeeTransactionSignerPolicy.LegacyExclusiveSource)
      verified <- validator.validate(many, verifiedExclusive)
    } yield
      expect(many.proofs.length > 16L, s"fixture must exceed the cap: ${many.proofs.length}")
        .and(expect(legacy.isValid, s"the legacy rule has no cap: $legacy"))
        .and(expect(verified.isInvalid, s"from the boundary the proof count is capped: $verified"))
  }
}
