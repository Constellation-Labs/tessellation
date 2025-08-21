package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.{Signed, signature}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalTokenLockAcceptanceManagerSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], SecurityProvider[IO])

  // Test data
  val testSignature = signature.Signature(Hex(""))
  val testSignatureProof = signature.SignatureProof(Id(Hex("")), testSignature)
  val testProofs = NonEmptySet.one(testSignatureProof)

  override def sharedResource: Resource[IO, Res] =
    for {
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
    } yield (h, sp)

  test("acceptReplacementTokenLocks - should accept token locks without replacement reference") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(1000L).some,
        none // No replacement reference
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      snapshotInfo = GlobalSnapshotInfo.empty

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedTokenLock), snapshotInfo)
    } yield expect(result == List(signedTokenLock))
  }

  test("acceptReplacementTokenLocks - should filter out token locks with invalid replacement references") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(1000L).some,
        Hash("invalidRef").some // Invalid replacement reference
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      snapshotInfo = GlobalSnapshotInfo.empty // Empty snapshot, no active token locks

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedTokenLock), snapshotInfo)
    } yield expect(result.isEmpty)
  }

  test("acceptReplacementTokenLocks - should accept token locks with valid replacement references") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref456")),
        none,
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedTokenLock), snapshotInfo)
    } yield expect(result == List(signedTokenLock))
  }

  test("acceptReplacementTokenLocks - should reject token locks with lower amount than existing") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(1000L).some,
        none
      )

      replacementTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L), // Lower amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(1000L).some,
        none // Will be set after computing hash
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      replacementTokenLockWithRef = replacementTokenLock.copy(
        replaceTokenLockRef = hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLockWithRef, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedReplacementTokenLock), snapshotInfo)
    } yield expect(result.isEmpty)
  }

  test("acceptReplacementTokenLocks - should handle mixed token locks with and without replacement references") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]
    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      tokenLockWithoutReplacement = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref1")),
        none,
        EpochProgress(1000L).some,
        none // No replacement reference
      )
      signedTokenLockWithoutReplacement <- Signed.forAsyncHasher(tokenLockWithoutReplacement, kp)

      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(50L), // Lower amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref2")),
        none,
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed
      replacementTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref3")),
        none,
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some // References the existing token lock
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLock, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      mixedTokenLocks = List(signedTokenLockWithoutReplacement, signedReplacementTokenLock)

      result <- acceptanceManager.acceptReplacementTokenLocks(mixedTokenLocks, snapshotInfo)
    } yield expect(result == List(signedTokenLockWithoutReplacement, signedReplacementTokenLock))
  }

  test("acceptReplacementTokenLocks - should reject replacement token locks with non-empty currency ID") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress
      currencyAddress <- KeyPairGenerator.makeKeyPair[IO].flatMap(_.getPublic.toId.toAddress)

      // Create existing token lock with empty currency ID
      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      // Create replacement token lock with non-empty currency ID
      replacementTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        CurrencyId(currencyAddress).some, // Non-empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLock, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedReplacementTokenLock), snapshotInfo)
    } yield expect(result.isEmpty)
  }

  test("acceptReplacementTokenLocks - should reject replacement token locks with different source address") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      sourceAddress1 <- kp1.getPublic.toId.toAddress
      sourceAddress2 <- kp2.getPublic.toId.toAddress

      // Create existing token lock with empty currency ID
      existingTokenLock = TokenLock(
        sourceAddress1,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp1)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      // Create replacement token lock with different source address
      replacementTokenLock = TokenLock(
        sourceAddress2, // Different source address
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLock, kp2)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          sourceAddress1 -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedReplacementTokenLock), snapshotInfo)
    } yield expect(result.isEmpty) // Should reject because source addresses don't match
  }

  test("acceptReplacementTokenLocks - should accept replacement token locks with empty currency ID and matching source address") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create existing token lock with empty currency ID
      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      // Create replacement token lock with empty currency ID and matching source address
      replacementTokenLock = TokenLock(
        testAddress, // Same source address
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLock, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedReplacementTokenLock), snapshotInfo)
    } yield expect(result == List(signedReplacementTokenLock)) // Should accept
  }

  test("acceptReplacementTokenLocks - should reject replacement token locks when existing token lock has non-empty currency ID") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress
      currencyAddress <- KeyPairGenerator.makeKeyPair[IO].flatMap(_.getPublic.toId.toAddress)

      // Create existing token lock with non-empty currency ID
      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        CurrencyId(currencyAddress).some, // Non-empty currency ID
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      // Create replacement token lock with empty currency ID
      replacementTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLock, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(List(signedReplacementTokenLock), snapshotInfo)
    } yield expect(result.isEmpty)
  }

  test("acceptReplacementTokenLocks - should handle multiple token locks with mixed currency ID states") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress
      currencyAddress <- KeyPairGenerator.makeKeyPair[IO].flatMap(_.getPublic.toId.toAddress)

      // Create existing token lock with empty currency ID
      existingTokenLockEmptyCurrency = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        none
      )

      // Create existing token lock with non-empty currency ID
      existingTokenLockWithCurrency = TokenLock(
        testAddress,
        TokenLockAmount(50L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        CurrencyId(currencyAddress).some, // Non-empty currency ID
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLockEmptyCurrency <- Signed.forAsyncHasher(existingTokenLockEmptyCurrency, kp)
      signedExistingTokenLockWithCurrency <- Signed.forAsyncHasher(existingTokenLockWithCurrency, kp)
      hashedExistingTokenLockEmptyCurrency <- signedExistingTokenLockEmptyCurrency.toHashed
      hashedExistingTokenLockWithCurrency <- signedExistingTokenLockWithCurrency.toHashed

      // Create replacement token lock for empty currency ID token lock (should be accepted)
      replacementTokenLockEmptyCurrency = TokenLock(
        testAddress,
        TokenLockAmount(200L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref789")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLockEmptyCurrency.hash.some
      )

      // Create replacement token lock for non-empty currency ID token lock (should be accepted because replacement has empty currency ID)
      replacementTokenLockWithCurrency = TokenLock(
        testAddress,
        TokenLockAmount(300L), // Higher amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(4L), Hash("ref101")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLockWithCurrency.hash.some
      )

      signedReplacementTokenLockEmptyCurrency <- Signed.forAsyncHasher(replacementTokenLockEmptyCurrency, kp)
      signedReplacementTokenLockWithCurrency <- Signed.forAsyncHasher(replacementTokenLockWithCurrency, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLockEmptyCurrency, signedExistingTokenLockWithCurrency)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(
        List(signedReplacementTokenLockEmptyCurrency, signedReplacementTokenLockWithCurrency),
        snapshotInfo
      )
    } yield
      expect(
        result == List(signedReplacementTokenLockEmptyCurrency)
      ) // Only the first should be accepted because the second tries to replace a token lock with non-empty currency ID
  }

  test("acceptReplacementTokenLocks - should reject replacement token locks with lower or equal amount") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create existing token lock with empty currency ID
      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        none
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      // Create replacement token lock with equal amount (should be rejected)
      replacementTokenLockEqual = TokenLock(
        testAddress,
        TokenLockAmount(100L), // Equal amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      // Create replacement token lock with lower amount (should be rejected)
      replacementTokenLockLower = TokenLock(
        testAddress,
        TokenLockAmount(50L), // Lower amount
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref789")),
        none, // Empty currency ID
        EpochProgress(1000L).some,
        hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLockEqual <- Signed.forAsyncHasher(replacementTokenLockEqual, kp)
      signedReplacementTokenLockLower <- Signed.forAsyncHasher(replacementTokenLockLower, kp)

      snapshotInfo = GlobalSnapshotInfo.empty.copy(
        activeTokenLocks = SortedMap(
          testAddress -> SortedSet(signedExistingTokenLock)
        ).some
      )

      result <- acceptanceManager.acceptReplacementTokenLocks(
        List(signedReplacementTokenLockEqual, signedReplacementTokenLockLower),
        snapshotInfo
      )
    } yield expect(result.isEmpty) // Both should be rejected due to amount requirements
  }

  test("emitTokenUnlocks - should handle empty inputs") { res =>
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      result <- acceptanceManager.emitTokenUnlocks(SortedMap.empty, List.empty, Map.empty).pure[IO]
    } yield expect(result.isRight && result.toOption.get.isEmpty)
  }

  test("emitTokenUnlocks - should generate unlocks for expired withdrawals") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      pendingWithdrawal = PendingDelegatedStakeWithdrawal(
        Signed(
          UpdateDelegatedStake.Create(
            testAddress,
            PeerId(Hex("")),
            DelegatedStakeAmount(100L),
            DelegatedStakeFee(10L),
            Hash("tokenLockRef"),
            DelegatedStakeReference(DelegatedStakeOrdinal(1L), Hash("parent"))
          ),
          testProofs
        ),
        Amount(50L),
        SnapshotOrdinal(1L),
        EpochProgress(100L),
        Hash("tokenLockRef").some,
        DelegatedStakeAmount(100L).some
      )

      activeTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("tokenLockRef")),
        none,
        EpochProgress(1000L).some,
        none
      )

      signedActiveTokenLock <- Signed.forAsyncHasher(activeTokenLock, kp)

      expiredWithdrawals = SortedMap(
        testAddress -> SortedSet(pendingWithdrawal)
      )

      globalActiveTokenLocksByRef = Map(Hash("tokenLockRef") -> signedActiveTokenLock)

      result <- acceptanceManager.emitTokenUnlocks(expiredWithdrawals, List.empty, globalActiveTokenLocksByRef).pure[IO]
    } yield expect.eql(Right(Map(testAddress -> List(TokenUnlock(Hash("tokenLockRef"), TokenLockAmount(100L), none, testAddress)))), result)
  }

  test("emitTokenUnlocks - should generate unlocks for token locks with replacement references") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      existingTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(1000L).some,
        none
      )

      replacementTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(1000L).some,
        none // Will be set after computing hash
      )

      signedExistingTokenLock <- Signed.forAsyncHasher(existingTokenLock, kp)
      hashedExistingTokenLock <- signedExistingTokenLock.toHashed

      replacementTokenLockWithRef = replacementTokenLock.copy(
        replaceTokenLockRef = hashedExistingTokenLock.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLockWithRef, kp)

      globalActiveTokenLocksByRef = Map(hashedExistingTokenLock.hash -> signedExistingTokenLock)

      result <- acceptanceManager
        .emitTokenUnlocks(SortedMap.empty, List(signedReplacementTokenLock), globalActiveTokenLocksByRef)
        .pure[IO]
    } yield
      expect.eql(
        Right(Map(testAddress -> List(TokenUnlock(hashedExistingTokenLock.hash, TokenLockAmount(100L), none, testAddress)))),
        result
      )
  }

  test("emitTokenUnlocks - should return error for missing token lock") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress
      pendingWithdrawal = PendingDelegatedStakeWithdrawal(
        Signed(
          UpdateDelegatedStake.Create(
            testAddress,
            PeerId(Hex("")),
            DelegatedStakeAmount(100L),
            DelegatedStakeFee(10L),
            Hash("missingTokenLockRef"),
            DelegatedStakeReference(DelegatedStakeOrdinal(1L), Hash("parent"))
          ),
          testProofs
        ),
        Amount(50L),
        SnapshotOrdinal(1L),
        EpochProgress(100L),
        Hash("missingTokenLockRef").some,
        DelegatedStakeAmount(100L).some
      )

      expiredWithdrawals = SortedMap(
        testAddress -> SortedSet(pendingWithdrawal)
      )

      globalActiveTokenLocksByRef = Map.empty[Hash, Signed[TokenLock]] // Empty map, missing token lock

      result <- acceptanceManager.emitTokenUnlocks(expiredWithdrawals, List.empty, globalActiveTokenLocksByRef).pure[IO]
    } yield expect(result.isLeft)
  }

  test("emitTokenUnlocks - should combine expired withdrawals and replacement unlocks") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      pendingWithdrawal = PendingDelegatedStakeWithdrawal(
        Signed(
          UpdateDelegatedStake.Create(
            testAddress,
            PeerId(Hex("")),
            DelegatedStakeAmount(100L),
            DelegatedStakeFee(10L),
            Hash("tokenLockRef1"),
            DelegatedStakeReference(DelegatedStakeOrdinal(1L), Hash("parent"))
          ),
          testProofs
        ),
        Amount(50L),
        SnapshotOrdinal(1L),
        EpochProgress(100L),
        Hash("tokenLockRef1").some,
        DelegatedStakeAmount(100L).some
      )

      activeTokenLock1 = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("tokenLockRef1")),
        none,
        EpochProgress(1000L).some,
        none
      )

      activeTokenLock2 = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("tokenLockRef2")),
        none,
        EpochProgress(1000L).some,
        none
      )

      replacementTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(300L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref456")),
        none,
        EpochProgress(1000L).some,
        none // Will be set after computing hash
      )

      signedActiveTokenLock1 <- Signed.forAsyncHasher(activeTokenLock1, kp)
      signedActiveTokenLock2 <- Signed.forAsyncHasher(activeTokenLock2, kp)
      hashedActiveTokenLock1 <- signedActiveTokenLock1.toHashed
      hashedActiveTokenLock2 <- signedActiveTokenLock2.toHashed

      replacementTokenLockWithRef = replacementTokenLock.copy(
        replaceTokenLockRef = hashedActiveTokenLock2.hash.some
      )

      signedReplacementTokenLock <- Signed.forAsyncHasher(replacementTokenLockWithRef, kp)

      pendingWithdrawalWithCorrectRef = pendingWithdrawal.copy(
        currentTokenLockRef = hashedActiveTokenLock1.hash.some
      )

      expiredWithdrawals = SortedMap(
        testAddress -> SortedSet(pendingWithdrawalWithCorrectRef)
      )

      globalActiveTokenLocksByRef = Map(
        hashedActiveTokenLock1.hash -> signedActiveTokenLock1,
        hashedActiveTokenLock2.hash -> signedActiveTokenLock2
      )

      result <- acceptanceManager
        .emitTokenUnlocks(expiredWithdrawals, List(signedReplacementTokenLock), globalActiveTokenLocksByRef)
        .pure[IO]
    } yield
      expect(
        Right(
          Map(
            testAddress ->
              List(
                TokenUnlock(
                  hashedActiveTokenLock1.hash,
                  TokenLockAmount(100L),
                  none,
                  testAddress
                ),
                TokenUnlock(
                  hashedActiveTokenLock2.hash,
                  TokenLockAmount(200L),
                  none,
                  testAddress
                )
              )
          )
        ) == result
      ) // Should have 2 unlocks
  }

  test("filterExpiredTokenLocks - should return empty map for empty input") { res =>
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]
    val currentEpoch = EpochProgress(1000L)
    val emptyTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]

    for {
      result <- IO.pure(acceptanceManager.filterExpiredTokenLocks(emptyTokenLocks, currentEpoch))
    } yield expect(result.isEmpty)
  }

  test("filterExpiredTokenLocks - should filter out token locks with no unlockEpoch") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock with no unlockEpoch (indefinite lock)
      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        none, // No unlockEpoch
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      currentEpoch = EpochProgress(1000L)

      tokenLocks = SortedMap(
        testAddress -> SortedSet(signedTokenLock)
      )

      result = acceptanceManager.filterExpiredTokenLocks(tokenLocks, currentEpoch)
    } yield expect(result == SortedMap(testAddress -> SortedSet.empty[Signed[TokenLock]])) // Should have address with empty set
  }

  test("filterExpiredTokenLocks - should filter out token locks with future unlockEpoch") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock with future unlockEpoch
      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some, // Future unlockEpoch
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      currentEpoch = EpochProgress(1000L)

      tokenLocks = SortedMap(
        testAddress -> SortedSet(signedTokenLock)
      )

      result = acceptanceManager.filterExpiredTokenLocks(tokenLocks, currentEpoch)
    } yield expect(result == SortedMap(testAddress -> SortedSet.empty[Signed[TokenLock]])) // Should have address with empty set
  }

  test("filterExpiredTokenLocks - should include token locks with past unlockEpoch") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock with past unlockEpoch
      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      currentEpoch = EpochProgress(1000L)

      tokenLocks = SortedMap(
        testAddress -> SortedSet(signedTokenLock)
      )

      result = acceptanceManager.filterExpiredTokenLocks(tokenLocks, currentEpoch)
    } yield expect(result == SortedMap(testAddress -> SortedSet(signedTokenLock))) // Should include expired token lock
  }

  test("filterExpiredTokenLocks - should include token locks with current unlockEpoch") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock with current unlockEpoch
      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(1000L).some, // Current unlockEpoch
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      currentEpoch = EpochProgress(1000L)

      tokenLocks = SortedMap(
        testAddress -> SortedSet(signedTokenLock)
      )

      result = acceptanceManager.filterExpiredTokenLocks(tokenLocks, currentEpoch)
    } yield expect(result == SortedMap(testAddress -> SortedSet.empty[Signed[TokenLock]])) // Should have address with empty set
  }

  test("filterExpiredTokenLocks - should handle mixed token locks with different unlockEpochs") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock with no unlockEpoch
      tokenLockNoEpoch = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        none, // No unlockEpoch
        none
      )

      // Create token lock with past unlockEpoch
      tokenLockExpired = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      // Create token lock with future unlockEpoch
      tokenLockFuture = TokenLock(
        testAddress,
        TokenLockAmount(300L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref789")),
        none,
        EpochProgress(2000L).some, // Future unlockEpoch
        none
      )

      signedTokenLockNoEpoch <- Signed.forAsyncHasher(tokenLockNoEpoch, kp)
      signedTokenLockExpired <- Signed.forAsyncHasher(tokenLockExpired, kp)
      signedTokenLockFuture <- Signed.forAsyncHasher(tokenLockFuture, kp)

      currentEpoch = EpochProgress(1000L)

      tokenLocks = SortedMap(
        testAddress -> SortedSet(signedTokenLockNoEpoch, signedTokenLockExpired, signedTokenLockFuture)
      )

      result = acceptanceManager.filterExpiredTokenLocks(tokenLocks, currentEpoch)
    } yield expect(result == SortedMap(testAddress -> SortedSet(signedTokenLockExpired))) // Should only include expired token lock
  }

  test("filterExpiredTokenLocks - should handle multiple addresses") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      address1 <- kp1.getPublic.toId.toAddress
      address2 <- kp2.getPublic.toId.toAddress

      // Create token lock for address1 with past unlockEpoch
      tokenLock1Expired = TokenLock(
        address1,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      // Create token lock for address1 with future unlockEpoch
      tokenLock1Future = TokenLock(
        address1,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(2000L).some, // Future unlockEpoch
        none
      )

      // Create token lock for address2 with past unlockEpoch
      tokenLock2Expired = TokenLock(
        address2,
        TokenLockAmount(300L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref789")),
        none,
        EpochProgress(300L).some, // Past unlockEpoch
        none
      )

      // Create token lock for address2 with no unlockEpoch
      tokenLock2NoEpoch = TokenLock(
        address2,
        TokenLockAmount(400L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(4L), Hash("ref101")),
        none,
        none, // No unlockEpoch
        none
      )

      signedTokenLock1Expired <- Signed.forAsyncHasher(tokenLock1Expired, kp1)
      signedTokenLock1Future <- Signed.forAsyncHasher(tokenLock1Future, kp1)
      signedTokenLock2Expired <- Signed.forAsyncHasher(tokenLock2Expired, kp2)
      signedTokenLock2NoEpoch <- Signed.forAsyncHasher(tokenLock2NoEpoch, kp2)

      currentEpoch = EpochProgress(1000L)

      tokenLocks = SortedMap(
        address1 -> SortedSet(signedTokenLock1Expired, signedTokenLock1Future),
        address2 -> SortedSet(signedTokenLock2Expired, signedTokenLock2NoEpoch)
      )

      result = acceptanceManager.filterExpiredTokenLocks(tokenLocks, currentEpoch)
    } yield
      expect(
        result == SortedMap(
          address1 -> SortedSet(signedTokenLock1Expired),
          address2 -> SortedSet(signedTokenLock2Expired)
        )
      ) // Should only include expired token locks from both addresses
  }

  test("acceptTokenLocks - should handle empty inputs") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]
    val currentEpoch = EpochProgress(1000L)
    val emptyTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
    val emptyUnlocks = Map.empty[Address, List[TokenUnlock]]

    for {
      result <- acceptanceManager.acceptTokenLocks(currentEpoch, emptyTokenLocks, emptyTokenLocks, emptyUnlocks)
    } yield expect(result.isEmpty)
  }

  test("acceptTokenLocks - should accept new token locks and filter out expired ones") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create new token lock with future unlockEpoch
      newTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some, // Future unlockEpoch
        none
      )

      // Create existing token lock with past unlockEpoch (expired)
      expiredTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      // Create existing token lock with future unlockEpoch (not expired)
      activeTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(300L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref789")),
        none,
        EpochProgress(1500L).some, // Future unlockEpoch
        none
      )

      signedNewTokenLock <- Signed.forAsyncHasher(newTokenLock, kp)
      signedExpiredTokenLock <- Signed.forAsyncHasher(expiredTokenLock, kp)
      signedActiveTokenLock <- Signed.forAsyncHasher(activeTokenLock, kp)

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedNewTokenLock)
      )

      lastActiveGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedExpiredTokenLock, signedActiveTokenLock)
      )

      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          testAddress -> SortedSet(signedNewTokenLock, signedActiveTokenLock)
        )
      ) // Should include new token lock and active token lock, but not expired token lock
  }

  test("acceptTokenLocks - should remove token locks that are in generated unlocks") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock that will be unlocked
      tokenLockToUnlock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create token lock that will remain
      tokenLockToKeep = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(2000L).some,
        none
      )

      signedTokenLockToUnlock <- Signed.forAsyncHasher(tokenLockToUnlock, kp)
      signedTokenLockToKeep <- Signed.forAsyncHasher(tokenLockToKeep, kp)
      hashedTokenLockToUnlock <- signedTokenLockToUnlock.toHashed

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]

      lastActiveGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedTokenLockToUnlock, signedTokenLockToKeep)
      )

      generatedTokenUnlocksByAddress = Map(
        testAddress -> List(
          TokenUnlock(
            hashedTokenLockToUnlock.hash,
            TokenLockAmount(100L),
            none,
            testAddress
          )
        )
      )

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          testAddress -> SortedSet(signedTokenLockToKeep)
        )
      ) // Should only include token lock that is not being unlocked
  }

  test("acceptTokenLocks - should handle multiple addresses") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      address1 <- kp1.getPublic.toId.toAddress
      address2 <- kp2.getPublic.toId.toAddress

      // Create token locks for address1
      tokenLock1 = TokenLock(
        address1,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create token locks for address2
      tokenLock2 = TokenLock(
        address2,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(2000L).some,
        none
      )

      signedTokenLock1 <- Signed.forAsyncHasher(tokenLock1, kp1)
      signedTokenLock2 <- Signed.forAsyncHasher(tokenLock2, kp2)

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap(
        address1 -> SortedSet(signedTokenLock1)
      )

      lastActiveGlobalTokenLocks = SortedMap(
        address2 -> SortedSet(signedTokenLock2)
      )

      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          address1 -> SortedSet(signedTokenLock1),
          address2 -> SortedSet(signedTokenLock2)
        )
      ) // Should include token locks from both addresses
  }

  test("acceptTokenLocks - should filter out addresses with empty token lock sets") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      address1 <- kp1.getPublic.toId.toAddress
      address2 <- kp2.getPublic.toId.toAddress

      // Create token lock for address1
      tokenLock1 = TokenLock(
        address1,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create expired token lock for address2
      expiredTokenLock2 = TokenLock(
        address2,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      signedTokenLock1 <- Signed.forAsyncHasher(tokenLock1, kp1)
      signedExpiredTokenLock2 <- Signed.forAsyncHasher(expiredTokenLock2, kp2)

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]

      lastActiveGlobalTokenLocks = SortedMap(
        address1 -> SortedSet(signedTokenLock1),
        address2 -> SortedSet(signedExpiredTokenLock2)
      )

      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          address1 -> SortedSet(signedTokenLock1)
        )
      ) // Should only include address1, address2 should be filtered out due to empty set
  }

  test("acceptTokenLocks - should handle token locks with no unlockEpoch") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create token lock with no unlockEpoch (indefinite lock)
      indefiniteTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        none, // No unlockEpoch
        none
      )

      signedIndefiniteTokenLock <- Signed.forAsyncHasher(indefiniteTokenLock, kp)

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedIndefiniteTokenLock)
      )

      lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]

      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          testAddress -> SortedSet(signedIndefiniteTokenLock)
        )
      ) // Should include indefinite token lock
  }

  test("acceptTokenLocks - should combine accepted and expired token locks correctly") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create accepted token lock
      acceptedTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create expired token lock (should be included from lastActiveGlobalTokenLocks)
      expiredTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      signedAcceptedTokenLock <- Signed.forAsyncHasher(acceptedTokenLock, kp)
      signedExpiredTokenLock <- Signed.forAsyncHasher(expiredTokenLock, kp)

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedAcceptedTokenLock)
      )

      lastActiveGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedExpiredTokenLock)
      )

      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          testAddress -> SortedSet(signedAcceptedTokenLock)
        )
      ) // Should only include accepted token lock, expired token lock is filtered out
  }

  test("acceptTokenLocks - should handle complex scenario with multiple operations") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      // Create new token lock
      newTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create active token lock
      activeTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create token lock to be unlocked
      tokenLockToUnlock = TokenLock(
        testAddress,
        TokenLockAmount(300L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(3L), Hash("ref789")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Create expired token lock
      expiredTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(400L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(4L), Hash("ref101")),
        none,
        EpochProgress(500L).some, // Past unlockEpoch
        none
      )

      signedNewTokenLock <- Signed.forAsyncHasher(newTokenLock, kp)
      signedActiveTokenLock <- Signed.forAsyncHasher(activeTokenLock, kp)
      signedTokenLockToUnlock <- Signed.forAsyncHasher(tokenLockToUnlock, kp)
      signedExpiredTokenLock <- Signed.forAsyncHasher(expiredTokenLock, kp)
      hashedTokenLockToUnlock <- signedTokenLockToUnlock.toHashed

      currentEpoch = EpochProgress(1000L)

      acceptedGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedNewTokenLock)
      )

      lastActiveGlobalTokenLocks = SortedMap(
        testAddress -> SortedSet(signedActiveTokenLock, signedTokenLockToUnlock, signedExpiredTokenLock)
      )

      generatedTokenUnlocksByAddress = Map(
        testAddress -> List(
          TokenUnlock(
            hashedTokenLockToUnlock.hash,
            TokenLockAmount(300L),
            none,
            testAddress
          )
        )
      )

      result <- acceptanceManager.acceptTokenLocks(
        currentEpoch,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield
      expect(
        result == SortedMap(
          testAddress -> SortedSet(signedNewTokenLock, signedActiveTokenLock)
        )
      ) // Should include new and active token locks, but not expired or unlocked ones
  }

  test("updateGlobalBalancesByTokenLocks - should handle empty inputs") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    val epochProgress = EpochProgress(1000L)
    val currentBalances = SortedMap.empty[Address, Balance]
    val acceptedGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
    val lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
    val generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

    val result = acceptanceManager.updateGlobalBalancesByTokenLocks(
      epochProgress,
      currentBalances,
      acceptedGlobalTokenLocks,
      lastActiveGlobalTokenLocks,
      generatedTokenUnlocksByAddress
    )

    (expect(result.isRight) &&
      expect(result.toOption.get.isEmpty)).pure[IO]
  }

  test("updateGlobalBalancesByTokenLocks - should deduct amounts for new token locks") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(1000L)
      currentBalances = SortedMap(testAddress -> initialBalance)

      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      acceptedGlobalTokenLocks = SortedMap(testAddress -> SortedSet(signedTokenLock))
      lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      expectedBalance = Balance(890L) // 1000 - 100 - 10
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(testAddress) == expectedBalance)
  }

  test("updateGlobalBalancesByTokenLocks - should add back amounts for expired token locks") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(500L)
      currentBalances = SortedMap(testAddress -> initialBalance)

      expiredTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(999L).some, // Past epoch
        none
      )

      signedExpiredTokenLock <- Signed.forAsyncHasher(expiredTokenLock, kp)
      acceptedGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
      lastActiveGlobalTokenLocks = SortedMap(testAddress -> SortedSet(signedExpiredTokenLock))
      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      expectedBalance = Balance(600L) // 500 + 100 (only amount, not fee)
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(testAddress) == expectedBalance)
  }

  test("updateGlobalBalancesByTokenLocks - should add amounts for generated token unlocks") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(500L)
      currentBalances = SortedMap(testAddress -> initialBalance)

      acceptedGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
      lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]

      tokenUnlock = TokenUnlock(Hash("ref123"), TokenLockAmount(100L), none, testAddress)
      generatedTokenUnlocksByAddress = Map(testAddress -> List(tokenUnlock))

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      expectedBalance = Balance(600L) // 500 + 100
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(testAddress) == expectedBalance)
  }

  test("updateGlobalBalancesByTokenLocks - should handle balance arithmetic errors") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(50L) // Small balance
      currentBalances = SortedMap(testAddress -> initialBalance)

      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L), // Larger than balance
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      acceptedGlobalTokenLocks = SortedMap(testAddress -> SortedSet(signedTokenLock))
      lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )
    } yield expect(result.isLeft) // Should fail due to insufficient balance
  }

  test("updateGlobalBalancesByTokenLocks - should handle complex scenario with all operations") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(1000L)
      currentBalances = SortedMap(testAddress -> initialBalance)

      // New token lock (should deduct amount + fee)
      newTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Expired token lock (should add back amount only)
      expiredTokenLock = TokenLock(
        testAddress,
        TokenLockAmount(200L),
        TokenLockFee(20L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(999L).some, // Past epoch
        none
      )

      signedNewTokenLock <- Signed.forAsyncHasher(newTokenLock, kp)
      signedExpiredTokenLock <- Signed.forAsyncHasher(expiredTokenLock, kp)

      acceptedGlobalTokenLocks = SortedMap(testAddress -> SortedSet(signedNewTokenLock))
      lastActiveGlobalTokenLocks = SortedMap(testAddress -> SortedSet(signedExpiredTokenLock))

      // Generated token unlock (should add amount)
      tokenUnlock = TokenUnlock(Hash("ref789"), TokenLockAmount(50L), none, testAddress)
      generatedTokenUnlocksByAddress = Map(testAddress -> List(tokenUnlock))

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      // Expected: 1000 - 100 - 10 + 200 + 50 = 1140
      expectedBalance = Balance(1140L)
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(testAddress) == expectedBalance)
  }

  test("updateGlobalBalancesByTokenLocks - should handle multiple addresses") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      address1 <- kp1.getPublic.toId.toAddress
      address2 <- kp2.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(500L)
      currentBalances = SortedMap(
        address1 -> initialBalance,
        address2 -> initialBalance
      )

      // Address 1: New token lock
      tokenLock1 = TokenLock(
        address1,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        EpochProgress(2000L).some,
        none
      )

      // Address 2: Expired token lock
      tokenLock2 = TokenLock(
        address2,
        TokenLockAmount(200L),
        TokenLockFee(20L),
        TokenLockReference(TokenLockOrdinal(2L), Hash("ref456")),
        none,
        EpochProgress(999L).some, // Past epoch
        none
      )

      signedTokenLock1 <- Signed.forAsyncHasher(tokenLock1, kp1)
      signedTokenLock2 <- Signed.forAsyncHasher(tokenLock2, kp2)

      acceptedGlobalTokenLocks = SortedMap(address1 -> SortedSet(signedTokenLock1))
      lastActiveGlobalTokenLocks = SortedMap(address2 -> SortedSet(signedTokenLock2))
      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      expectedBalance1 = Balance(390L) // 500 - 100 - 10
      expectedBalance2 = Balance(700L) // 500 + 200
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(address1) == expectedBalance1) &&
        expect(result.toOption.get(address2) == expectedBalance2)
  }

  test("updateGlobalBalancesByTokenLocks - should handle token locks without unlock epoch") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(1000L)
      currentBalances = SortedMap(testAddress -> initialBalance)

      // Token lock without unlock epoch (should be treated as unexpired)
      tokenLock = TokenLock(
        testAddress,
        TokenLockAmount(100L),
        TokenLockFee(10L),
        TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
        none,
        none, // No unlock epoch
        none
      )

      signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)
      acceptedGlobalTokenLocks = SortedMap(testAddress -> SortedSet(signedTokenLock))
      lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
      generatedTokenUnlocksByAddress = Map.empty[Address, List[TokenUnlock]]

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      expectedBalance = Balance(890L) // 1000 - 100 - 10
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(testAddress) == expectedBalance)
  }

  test("updateGlobalBalancesByTokenLocks - should handle multiple token unlocks for same address") { res =>
    implicit val (jsonHasher, sp) = res
    val acceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      testAddress <- kp.getPublic.toId.toAddress

      epochProgress = EpochProgress(1000L)
      initialBalance = Balance(500L)
      currentBalances = SortedMap(testAddress -> initialBalance)

      acceptedGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
      lastActiveGlobalTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]

      // Multiple token unlocks for the same address
      tokenUnlock1 = TokenUnlock(Hash("ref123"), TokenLockAmount(100L), none, testAddress)
      tokenUnlock2 = TokenUnlock(Hash("ref456"), TokenLockAmount(200L), none, testAddress)
      tokenUnlock3 = TokenUnlock(Hash("ref789"), TokenLockAmount(50L), none, testAddress)

      generatedTokenUnlocksByAddress = Map(testAddress -> List(tokenUnlock1, tokenUnlock2, tokenUnlock3))

      result = acceptanceManager.updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocksByAddress
      )

      expectedBalance = Balance(850L) // 500 + 100 + 200 + 50
    } yield
      expect(result.isRight) &&
        expect(result.toOption.get(testAddress) == expectedBalance)
  }

}
