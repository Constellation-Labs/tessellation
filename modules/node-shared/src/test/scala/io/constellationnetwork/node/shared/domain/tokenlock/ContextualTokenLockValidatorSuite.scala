package io.constellationnetwork.node.shared.domain.tokenlock

import java.security.KeyPair

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.TokenLocksConfig
import io.constellationnetwork.node.shared.domain.tokenlock.ContextualTokenLockValidator._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object ContextualTokenLockValidatorSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  test("valid token lock and empty context") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(10L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair)
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - sufficient balance with no existing token locks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(100L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - insufficient balance with no existing token locks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(50L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.same(InsufficientBalance(Amount(50L), Balance(50L)).invalidNec, res)
  }

  test("validate balances - exact balance with no existing token locks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(60L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - sufficient balance with existing non-majority token locks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(20L), TokenLockFee(5L))
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(30L), TokenLockFee(10L))
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      existingStoredTokenLock = WaitingTokenLock(existingHashedTokenLock)
      sourceTokenLocks = SortedMap(TokenLockOrdinal(1L) -> existingStoredTokenLock)

      context = TokenLockValidatorContext(
        sourceTokenLocks = sourceTokenLocks.some,
        sourceBalance = Balance(65L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - insufficient balance with existing non-majority token locks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]
      existingTokenLockRef <- TokenLockReference.of(existingTokenLock)

      newTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L), parent = existingTokenLockRef)
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      existingStoredTokenLock = WaitingTokenLock(existingHashedTokenLock)
      sourceTokenLocks = SortedMap(TokenLockOrdinal(1L) -> existingStoredTokenLock)

      context = TokenLockValidatorContext(
        sourceTokenLocks = sourceTokenLocks.some,
        sourceBalance = Balance(100L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.same(InsufficientBalance(Amount(50L), Balance(40L)).invalidNec, res)
  }

  test("validate balances - with replacement token lock reference") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(60L),
        TokenLockFee(5L),
        replaceTokenLockRef = existingHashedTokenLock.hash.some
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      existingStoredTokenLock = WaitingTokenLock(existingHashedTokenLock)
      sourceTokenLocks = SortedMap(TokenLockOrdinal(2L) -> existingStoredTokenLock)

      context = TokenLockValidatorContext(
        sourceTokenLocks = sourceTokenLocks.some,
        sourceBalance = Balance(100L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List(existingHashedTokenLock)
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - with replacement token lock reference and exact change") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      existingActiveTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(60L),
        TokenLockFee(10L),
        replaceTokenLockRef = existingActiveTokenLock.hash.some
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      context = TokenLockValidatorContext(
        sourceTokenLocks = none,
        sourceBalance = Balance(20L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List(existingActiveTokenLock)
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  // ==================== validateReplaceTokenLockRef tests ====================

  test("validateReplaceTokenLockRef - no replace ref passes") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(100L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L), replaceTokenLockRef = none)
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validateReplaceTokenLockRef - valid replacement with higher amount passes") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        replaceTokenLockRef = existingHashedTokenLock.hash.some
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      context = TokenLockValidatorContext(
        sourceTokenLocks = none,
        sourceBalance = Balance(200L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List(existingHashedTokenLock)
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validateReplaceTokenLockRef - NothingToReplace when ref not in hashedActiveTokenLocks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val nonExistentHash = Hash("0000000000000000000000000000000000000000000000000000000000000000")

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        replaceTokenLockRef = nonExistentHash.some
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      context = TokenLockValidatorContext(
        sourceTokenLocks = none,
        sourceBalance = Balance(200L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.same(NothingToReplace(nonExistentHash).invalidNec, res)
  }

  test("validateReplaceTokenLockRef - ReplacementLowerThanCurrentTokenLock when new amount less than existing") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(100L), TokenLockFee(10L))
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(50L),
        TokenLockFee(10L),
        replaceTokenLockRef = existingHashedTokenLock.hash.some
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      context = TokenLockValidatorContext(
        sourceTokenLocks = none,
        sourceBalance = Balance(200L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List(existingHashedTokenLock)
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield
      expect.same(
        ReplacementLowerThanCurrentTokenLock(TokenLockAmount(50L), TokenLockAmount(100L)).invalidNec,
        res
      )
  }

  test("validateReplaceTokenLockRef - ReplacementLowerThanCurrentTokenLock when new amount equals existing") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(100L), TokenLockFee(10L))
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        replaceTokenLockRef = existingHashedTokenLock.hash.some
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      context = TokenLockValidatorContext(
        sourceTokenLocks = none,
        sourceBalance = Balance(200L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List(existingHashedTokenLock)
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield
      expect.same(
        ReplacementLowerThanCurrentTokenLock(TokenLockAmount(100L), TokenLockAmount(100L)).invalidNec,
        res
      )
  }

  test("validateReplaceTokenLockRef - currency token lock replacement is now supported") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      currencyKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      currencyId = CurrencyId(currencyKeyPair.getPublic.toAddress).some
      validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), currencyId)

      existingTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L), currencyId = currencyId)
      existingHashedTokenLock <- existingTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(
        keyPair,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        replaceTokenLockRef = existingHashedTokenLock.hash.some,
        currencyId = currencyId
      )
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      context = TokenLockValidatorContext(
        sourceTokenLocks = none,
        sourceBalance = Balance(200L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List(existingHashedTokenLock)
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - arithmetic error with overflow") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(Long.MaxValue),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(Long.MaxValue), TokenLockFee(Long.MaxValue))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.same(TokenLockArithmeticError(Amount(Long.MaxValue), Balance(Long.MaxValue)).invalidNec, res)
  }

  test("validate balances - zero amount and fee") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(0L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(1L), TokenLockFee(0L))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.same(InsufficientBalance(Amount(1L), Balance(0L)).invalidNec, res)
  }

  test("validate balances - multiple existing token locks affecting balance") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      existingTokenLock1 <- buildSignedLockToken(keyPair, TokenLockAmount(20L), TokenLockFee(5L))
      existingHashedTokenLock1 <- existingTokenLock1.toHashed[IO]

      existingTokenLock2 <- buildSignedLockToken(keyPair, TokenLockAmount(30L), TokenLockFee(10L))
      existingHashedTokenLock2 <- existingTokenLock2.toHashed[IO]

      newTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(25L), TokenLockFee(5L))
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      existingStoredTokenLock1 = WaitingTokenLock(existingHashedTokenLock1)
      existingStoredTokenLock2 = WaitingTokenLock(existingHashedTokenLock2)
      sourceTokenLocks = SortedMap(
        TokenLockOrdinal(2L) -> existingStoredTokenLock1,
        TokenLockOrdinal(3L) -> existingStoredTokenLock2
      )

      context = TokenLockValidatorContext(
        sourceTokenLocks = sourceTokenLocks.some,
        sourceBalance = Balance(95L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - majority token locks should not affect balance calculation") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      majorityTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      majorityHashedTokenLock <- majorityTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(40L), TokenLockFee(5L))
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      // Majority token locks should not affect balance calculation
      majorityStoredTokenLock = MajorityTokenLock(TokenLockReference.of(majorityHashedTokenLock), SnapshotOrdinal.MinValue)
      sourceTokenLocks = SortedMap(TokenLockOrdinal(2L) -> majorityStoredTokenLock)

      context = TokenLockValidatorContext(
        sourceTokenLocks = sourceTokenLocks.some,
        sourceBalance = Balance(45L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - edge case with maximum values") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(Long.MaxValue),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(Long.MaxValue - 1), TokenLockFee(1L))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - edge case with minimum values") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)
    val context = TokenLockValidatorContext(
      sourceTokenLocks = none,
      sourceBalance = Balance(1L),
      sourceLastTokenLocksRef = TokenLockReference.empty,
      currentOrdinal = SnapshotOrdinal.MinValue,
      currentEpochProgress = EpochProgress.MinValue,
      hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
    )

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      tokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(1L), TokenLockFee(0L))
      hashedTokenLock <- tokenLock.toHashed[IO]
      res = validator.validate(hashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  test("validate balances - with mixed majority and non-majority token locks") { res =>
    implicit val (h, sp) = res

    val validator = ContextualTokenLockValidator.make(none, TokenLocksConfig(0L), none)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      majorityTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(50L), TokenLockFee(10L))
      majorityHashedTokenLock <- majorityTokenLock.toHashed[IO]

      nonMajorityTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(20L), TokenLockFee(5L))
      nonMajorityHashedTokenLock <- nonMajorityTokenLock.toHashed[IO]

      newTokenLock <- buildSignedLockToken(keyPair, TokenLockAmount(25L), TokenLockFee(5L))
      newHashedTokenLock <- newTokenLock.toHashed[IO]

      majorityStoredTokenLock = MajorityTokenLock(TokenLockReference.of(majorityHashedTokenLock), SnapshotOrdinal.MinValue)
      nonMajorityStoredTokenLock = WaitingTokenLock(nonMajorityHashedTokenLock)
      sourceTokenLocks = SortedMap(
        TokenLockOrdinal(2L) -> majorityStoredTokenLock,
        TokenLockOrdinal(3L) -> nonMajorityStoredTokenLock
      )

      context = TokenLockValidatorContext(
        sourceTokenLocks = sourceTokenLocks.some,
        sourceBalance = Balance(55L),
        sourceLastTokenLocksRef = TokenLockReference.empty,
        currentOrdinal = SnapshotOrdinal.MinValue,
        currentEpochProgress = EpochProgress.MinValue,
        hashedActiveTokenLocks = List.empty[Hashed[TokenLock]]
      )

      res = validator.validate(newHashedTokenLock, context)
    } yield expect.all(res.isValid)
  }

  def buildSignedLockToken(
    keyPair: KeyPair,
    amount: TokenLockAmount = TokenLockAmount(1L),
    fee: TokenLockFee = TokenLockFee(0L),
    replaceTokenLockRef: Option[Hash] = none,
    parent: TokenLockReference = TokenLockReference.empty,
    currencyId: Option[CurrencyId] = none
  )(implicit s: SecurityProvider[IO], h: Hasher[IO]): IO[Signed[TokenLock]] = {
    val src = keyPair.getPublic.toAddress
    val lastValidEpochProgress = EpochProgress(500L)

    val tokenLock = TokenLock(
      src,
      amount,
      fee,
      parent,
      currencyId,
      lastValidEpochProgress.some,
      replaceTokenLockRef
    )

    Signed.forAsyncHasher(tokenLock, keyPair)
  }
}
