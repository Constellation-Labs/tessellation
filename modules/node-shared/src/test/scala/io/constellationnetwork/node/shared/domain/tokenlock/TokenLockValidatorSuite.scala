package io.constellationnetwork.node.shared.domain.tokenlock

import java.security.KeyPair

import cats.data.Validated.{Invalid, Valid}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.SignedValidator.NotSignedExclusivelyByAddressOwner
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.PosInt
import weaver.MutableIOSuite

object TokenLockValidatorSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  def buildSignedTokenLock(
    keyPair: KeyPair,
    amount: TokenLockAmount = TokenLockAmount(100L),
    fee: TokenLockFee = TokenLockFee(0L),
    replaceTokenLockRef: Option[Hash] = none,
    parent: TokenLockReference = TokenLockReference.empty,
    currencyId: Option[CurrencyId] = none,
    unlockEpoch: Option[EpochProgress] = EpochProgress(500L).some
  )(implicit sp: SecurityProvider[IO], h: Hasher[IO]): IO[Signed[TokenLock]] = {
    val src = keyPair.getPublic.toAddress
    val tokenLock = TokenLock(
      src,
      amount,
      fee,
      parent,
      currencyId,
      unlockEpoch,
      replaceTokenLockRef
    )
    Signed.forAsyncHasher(tokenLock, keyPair)
  }

  def mkValidator(
    lockedAddresses: Set[Address] = Set.empty
  )(implicit sp: SecurityProvider[IO]): TokenLockValidator[IO] = {
    val signedValidator = SignedValidator.make[IO]
    val cfg = AddressesConfig(lockedAddresses)
    TokenLockValidator.make[IO](cfg, signedValidator)
  }

  // ==================== validate() tests ====================

  test("validate - should succeed for valid token lock with correct signature") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair)
      validator = mkValidator()
      result <- validator.validate(tokenLock, EpochProgress.MinValue.some)
    } yield expect.same(Valid(tokenLock), result)
  }

  test("validate - should succeed when epoch progress is None") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair)
      validator = mkValidator()
      result <- validator.validate(tokenLock, none)
    } yield expect.same(Valid(tokenLock), result)
  }

  test("validate - should fail when not signed by source address owner") { res =>
    implicit val (h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      // Create token lock with keyPair1's address but sign with keyPair2
      tokenLock <- buildSignedTokenLock(keyPair1)
      // Replace the signature with keyPair2's signature
      wronglySigned <- Signed.forAsyncHasher(tokenLock.value, keyPair2)
      validator = mkValidator()
      result <- validator.validate(wronglySigned, EpochProgress.MinValue.some)
    } yield
      expect.all(result match {
        case Invalid(errors) =>
          errors.exists {
            case InvalidSigned(NotSignedExclusivelyByAddressOwner) => true
            case NotSignedBySourceAddressOwner                     => true
            case _                                                 => false
          }
        case _ => false
      })
  }

  test("validate - should fail when token lock has multiple signatures") { res =>
    implicit val (h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair1)
      additionalSigned <- Signed.forAsyncHasher(tokenLock.value, keyPair2)
      multiSigned = tokenLock.addProof(additionalSigned.proofs.head)
      validator = mkValidator()
      result <- validator.validate(multiSigned, EpochProgress.MinValue.some)
    } yield expect.same(NotSignedBySourceAddressOwner.invalidNec, result)
  }

  test("validate - should fail when token lock is expired (unlockEpoch <= current epoch progress)") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      // Create token lock that unlocks at epoch 100
      tokenLock <- buildSignedTokenLock(keyPair, unlockEpoch = EpochProgress(100L).some)
      validator = mkValidator()
      // Current epoch progress is 100, so token lock is expired (unlockEpoch <= epochProgress)
      result <- validator.validate(tokenLock, EpochProgress(100L).some)
    } yield expect.same(TokenLockExpired.invalidNec, result)
  }

  test("validate - should fail when token lock is past expiration") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      // Create token lock that unlocks at epoch 50
      tokenLock <- buildSignedTokenLock(keyPair, unlockEpoch = EpochProgress(50L).some)
      validator = mkValidator()
      // Current epoch progress is 100, so token lock is expired
      result <- validator.validate(tokenLock, EpochProgress(100L).some)
    } yield expect.same(TokenLockExpired.invalidNec, result)
  }

  test("validate - should succeed when token lock is not yet expired") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      // Create token lock that unlocks at epoch 200
      tokenLock <- buildSignedTokenLock(keyPair, unlockEpoch = EpochProgress(200L).some)
      validator = mkValidator()
      // Current epoch progress is 100, so token lock is still valid
      result <- validator.validate(tokenLock, EpochProgress(100L).some)
    } yield expect.same(Valid(tokenLock), result)
  }

  test("validate - should succeed when token lock has no unlock epoch") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair, unlockEpoch = none)
      validator = mkValidator()
      result <- validator.validate(tokenLock, EpochProgress(100L).some)
    } yield expect.same(Valid(tokenLock), result)
  }

  test("validate - should fail when source address is locked") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      lockedAddress = keyPair.getPublic.toAddress
      tokenLock <- buildSignedTokenLock(keyPair)
      validator = mkValidator(lockedAddresses = Set(lockedAddress))
      result <- validator.validate(tokenLock, EpochProgress.MinValue.some)
    } yield expect.same(AddressLocked(lockedAddress).invalidNec, result)
  }

  test("validate - should succeed when different address is locked") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      otherKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      lockedAddress = otherKeyPair.getPublic.toAddress
      tokenLock <- buildSignedTokenLock(keyPair)
      validator = mkValidator(lockedAddresses = Set(lockedAddress))
      result <- validator.validate(tokenLock, EpochProgress.MinValue.some)
    } yield expect.same(Valid(tokenLock), result)
  }

  // ==================== validateWithTokenLockLimits() tests ====================

  test("validateWithTokenLockLimits - should succeed for valid token lock with no existing locks") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(10),
      minTokenLockAmount = PosLong.unsafeFrom(50L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(100L))
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(tokenLock, config, none, EpochProgress.MinValue.some)
    } yield expect.same(Valid(tokenLock), result)
  }

  test("validateWithTokenLockLimits - should fail when max token locks per address is reached") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(2),
      minTokenLockAmount = PosLong.unsafeFrom(10L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress

      existingLock1 <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(50L))
      existingLock2 <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(60L))

      currentTokenLocks = SortedMap(address -> SortedSet(existingLock1, existingLock2))

      newTokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(70L))
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(newTokenLock, config, currentTokenLocks.some, EpochProgress.MinValue.some)
    } yield expect.same(TooManyTokenLocksForAddress.invalidNec, result)
  }

  test("validateWithTokenLockLimits - should succeed for a replacement even at max token locks per address") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(2),
      minTokenLockAmount = PosLong.unsafeFrom(10L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress

      existingLock1 <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(50L))
      existingLock2 <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(60L))
      replaceRef <- existingLock1.toHashed.map(_.hash)

      currentTokenLocks = SortedMap(address -> SortedSet(existingLock1, existingLock2))

      replacement <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(70L), replaceTokenLockRef = replaceRef.some)
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(replacement, config, currentTokenLocks.some, EpochProgress.MinValue.some)
    } yield expect.same(Valid(replacement), result)
  }

  test("validateWithTokenLockLimits - should succeed when below max token locks per address") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(3),
      minTokenLockAmount = PosLong.unsafeFrom(10L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress

      existingLock1 <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(50L))

      currentTokenLocks = SortedMap(address -> SortedSet(existingLock1))

      newTokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(70L))
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(newTokenLock, config, currentTokenLocks.some, EpochProgress.MinValue.some)
    } yield expect.same(Valid(newTokenLock), result)
  }

  test("validateWithTokenLockLimits - should fail when amount is below minimum") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(10),
      minTokenLockAmount = PosLong.unsafeFrom(100L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(50L))
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(tokenLock, config, none, EpochProgress.MinValue.some)
    } yield expect.same(TokenLockAmountBelowMinimum.invalidNec, result)
  }

  test("validateWithTokenLockLimits - should succeed when amount equals minimum") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(10),
      minTokenLockAmount = PosLong.unsafeFrom(100L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(100L))
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(tokenLock, config, none, EpochProgress.MinValue.some)
    } yield expect.same(Valid(tokenLock), result)
  }

  // ==================== validateReplaceTokenLockRef tests ====================

  test("validateWithTokenLockLimits - should fail when replacement is not supported for currency token locks") { res =>
    implicit val (h, sp) = res

    val config = TokenLockLimitsConfig(
      maxTokenLocksPerAddress = PosInt.unsafeFrom(10),
      minTokenLockAmount = PosLong.unsafeFrom(10L)
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      currencyKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress
      currencyId = CurrencyId(currencyKeyPair.getPublic.toAddress).some

      existingTokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(50L), currencyId = currencyId)
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      currentTokenLocks = SortedMap(address -> SortedSet(existingTokenLock))

      newTokenLock <- buildSignedTokenLock(
        keyPair,
        amount = TokenLockAmount(100L),
        currencyId = currencyId,
        replaceTokenLockRef = existingHashedTokenLock.hash.some
      )
      validator = mkValidator()
      result <- validator.validateWithTokenLockLimits(newTokenLock, config, currentTokenLocks.some, EpochProgress.MinValue.some)
    } yield expect.same(ReplacementIsNotSupported(currencyId).invalidNec, result)
  }

  // ==================== Combined validation tests ====================

//  test("validateWithTokenLockLimits - should accumulate multiple validation errors") { res =>
//    implicit val (h, sp) = res
//
//    val config = TokenLockLimitsConfig(
//      maxTokenLocksPerAddress = PosInt.unsafeFrom(1),
//      minTokenLockAmount = PosLong.unsafeFrom(200L)
//    )
//
//    for {
//      keyPair <- KeyPairGenerator.makeKeyPair[IO]
//      address = keyPair.getPublic.toAddress
//      lockedAddress = address
//
//      existingLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(100L))
//      currentTokenLocks = SortedMap(address -> SortedSet(existingLock))
//
//      // This token lock has: locked address, below minimum amount, max locks reached
//      tokenLock <- buildSignedTokenLock(keyPair, amount = TokenLockAmount(50L))
//      validator = mkValidator(lockedAddresses = Set(lockedAddress))
//      result <- validator.validateWithTokenLockLimits(tokenLock, config, currentTokenLocks.some, EpochProgress.MinValue.some)
//      invalid = result.invalid[TokenLockValidationError]
//    } yield
//      expect.all(invalid.exists(_ == AddressLocked(lockedAddress)), invalid.exists(_ == TooManyTokenLocksForAddress))
//  }

//  test("validateWithTokenLockLimits - should validate expiration along with limits") { res =>
//    implicit val (h, sp) = res
//
//    val config = TokenLockLimitsConfig(
//      maxTokenLocksPerAddress = PosInt.unsafeFrom(10),
//      minTokenLockAmount = PosLong.unsafeFrom(10L)
//    )
//
//    for {
//      keyPair <- KeyPairGenerator.makeKeyPair[IO]
//      // Create expired token lock
//      tokenLock <- buildSignedTokenLock(keyPair, unlockEpoch = EpochProgress(50L).some)
//      validator = mkValidator()
//      // Current epoch is 100, so token lock is expired
//      result <- validator.validateWithTokenLockLimits(tokenLock, config, none, EpochProgress(100L).some)
//    } yield expect.same(TokenLockExpired.invalidNec, result)
//  }
}
