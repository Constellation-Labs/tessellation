package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.AllowSpendBlockAcceptanceMode
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.Mocks._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelValidationType

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object GlobalSnapshotAcceptanceManagerSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotAcceptanceManagerSuite.Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  val address1 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  val address2 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWC")
  val nodeId = PeerId(
    Hex("5dc4f7eba443f9a0dff11469b4fede358034abf20f9bbd8ea2b607179b72cfc159f33a64e24626891fc38b2c5a3dd7920c6b44a85cb745137b2e2e3e130adb5e")
  )

  test("should handle delegated stakes with replacement token locks") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(300L),
        replaceTokenLockRef = None,
        unlockEpoch = EpochProgress.MaxValue.some
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(keyPair, TokenLockAmount(400L), replaceTokenLockRef = originalHashedTokenLock.hash.some)
      replacementHashedTokenLock <- replacementTokenLock.toHashed

      // Create delegated stake event
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, originalHashedTokenLock)

      // Setup context with existing delegated stake
      existingDelegatedStakes = SortedMap(
        address1 -> SortedSet(
          DelegatedStakeRecord(
            event = delegatedStakeEvent,
            createdAt = SnapshotOrdinal(1L),
            rewards = Amount(10L),
            currentTokenLockRef = None,
            currentAmount = None
          )
        )
      )
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some,
        activeDelegatedStakes = existingDelegatedStakes.some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        tokenLockResult.accepted == List(tokenLockBlock),
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        newSnapshotInfo.activeDelegatedStakes.get.contains(address1),
        newSnapshotInfo.activeDelegatedStakes.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          replacementHashedTokenLock.hash
        ).some,
        newSnapshotInfo.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          replacementHashedTokenLock.amount.value.value
        ).some,
        lastSnapshotInfo.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.tokenLockRef)) == List(
          originalHashedTokenLock.hash
        ).some,
        lastSnapshotInfo.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          originalHashedTokenLock.amount.value.value
        ).some
      )
  }

  test("should handle withdrawals with expiration") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]

      // Create token lock
      tokenLock <- mkTokenLock(keyPair, TokenLockAmount(100L), replaceTokenLockRef = None)
      hashedTokenLock <- tokenLock.toHashed

      // Create delegated stake event
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedTokenLock)

      // Create withdrawals with different epochs
      recentWithdrawal = PendingDelegatedStakeWithdrawal(
        event = delegatedStakeEvent,
        rewards = Amount(10L),
        acceptedOrdinal = SnapshotOrdinal(1L),
        createdAt = EpochProgress(8L), // Recent, should not expire
        currentTokenLockRef = None,
        currentAmount = None
      )

      oldWithdrawal = PendingDelegatedStakeWithdrawal(
        event = delegatedStakeEvent,
        rewards = Amount(10L),
        acceptedOrdinal = SnapshotOrdinal(1L),
        createdAt = EpochProgress(1L), // Old, should expire
        currentTokenLockRef = None,
        currentAmount = None
      )

      // Setup context with withdrawals and token locks
      existingWithdrawals = SortedMap(
        address1 -> SortedSet(recentWithdrawal, oldWithdrawal)
      )

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(tokenLock)
      )

      lastSnapshotContext = mkGlobalSnapshotInfo(
        delegatedStakesWithdrawals = existingWithdrawals.some,
        activeTokenLocks = existingTokenLocks.some
      )

      manager <- mkManager(lastSnapshotContext.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotContext,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotContext),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        newSnapshotInfo.delegatedStakesWithdrawals.isDefined,
        newSnapshotInfo.delegatedStakesWithdrawals.get.contains(address1),
        newSnapshotInfo.delegatedStakesWithdrawals.get(address1) == SortedSet(recentWithdrawal) // Only recent withdrawal should remain
      )
  }

  test("should handle multiple addresses correctly") { res =>
    implicit val (h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic.toAddress
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic.toAddress

      // Create token locks for both addresses
      tokenLock1 <- mkTokenLock(keyPair1, TokenLockAmount(100L), replaceTokenLockRef = None)
      hashedTokenLock1 <- tokenLock1.toHashed
      tokenLock2 <- mkTokenLock(keyPair2, TokenLockAmount(200L), replaceTokenLockRef = None)
      hashedTokenLock2 <- tokenLock2.toHashed

      // Create delegated stake events
      delegatedStakeEvent1 <- mkDelegatedStakeCreate(keyPair1, nodeId, hashedTokenLock1)
      delegatedStakeEvent2 <- mkDelegatedStakeCreate(keyPair2, nodeId, hashedTokenLock2)

      // Create records for both addresses
      record1 = DelegatedStakeRecord(
        event = delegatedStakeEvent1,
        createdAt = SnapshotOrdinal(1L),
        rewards = Amount(10L),
        currentTokenLockRef = None,
        currentAmount = None
      )
      record2 = DelegatedStakeRecord(
        event = delegatedStakeEvent2,
        createdAt = SnapshotOrdinal(2L),
        rewards = Amount(20L),
        currentTokenLockRef = None,
        currentAmount = None
      )

      // Setup context
      existingDelegatedStakes = SortedMap(
        address1 -> SortedSet(record1),
        address2 -> SortedSet(record2)
      )

      lastSnapshotContext = mkGlobalSnapshotInfo(
        activeDelegatedStakes = existingDelegatedStakes.some
      )

      manager <- mkManager(lastSnapshotContext.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(3L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotContext,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotContext),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        newSnapshotInfo.activeDelegatedStakes.get.contains(address1),
        newSnapshotInfo.activeDelegatedStakes.get.contains(address2),
        newSnapshotInfo.activeDelegatedStakes.get(address1) == SortedSet(record1),
        newSnapshotInfo.activeDelegatedStakes.get(address2) == SortedSet(record2)
      )
  }

  test("should preserve existing currentTokenLockRef and currentAmount when no replacement") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create token lock
      tokenLock <- mkTokenLock(keyPair, TokenLockAmount(100L), replaceTokenLockRef = None)
      hashedTokenLock <- tokenLock.toHashed

      // Create delegated stake event
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedTokenLock)

      // Create record with existing currentTokenLockRef and currentAmount
      existingRecord = DelegatedStakeRecord(
        event = delegatedStakeEvent,
        createdAt = SnapshotOrdinal(1L),
        rewards = Amount(10L),
        currentTokenLockRef = Hash("existing").some,
        currentAmount = DelegatedStakeAmount(150L).some
      )

      // Setup context
      existingDelegatedStakes = SortedMap(
        address1 -> SortedSet(existingRecord)
      )

      lastSnapshotContext = mkGlobalSnapshotInfo(
        activeDelegatedStakes = existingDelegatedStakes.some
      )

      manager <- mkManager(lastSnapshotContext.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotContext,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotContext),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result

      updatedRecord = newSnapshotInfo.activeDelegatedStakes.get(address1).head
    } yield
      expect.all(
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        newSnapshotInfo.activeDelegatedStakes.get.contains(address1),
        updatedRecord.currentTokenLockRef.contains(Hash("existing")),
        updatedRecord.currentAmount.contains(DelegatedStakeAmount(150L))
      )
  }

  test("should handle delegated stake for original token lock creation and token lock replacement in same snapshot") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(keyPair, TokenLockAmount(300L), replaceTokenLockRef = originalHashedTokenLock.hash.some)
      replacementHashedTokenLock <- replacementTokenLock.toHashed

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some,
        updateNodeParameters = existingUpdateNodeParameters.some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      // Create delegated stake event for the original token lock
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, originalHashedTokenLock)

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should succeed
        tokenLockResult.accepted == List(tokenLockBlock),
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,

        // Delegated stake for the original token lock should be rejected
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        !newSnapshotInfo.activeDelegatedStakes.get.contains(address1)
      )
  }

  test("should handle delegated stake for replacement token lock creation and token lock replacement in same snapshot") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(keyPair, TokenLockAmount(300L), replaceTokenLockRef = originalHashedTokenLock.hash.some)
      replacementHashedTokenLock <- replacementTokenLock.toHashed

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some,
        updateNodeParameters = existingUpdateNodeParameters.some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      // Create delegated stake event for the replacement token lock
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, replacementHashedTokenLock)

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should succeed
        tokenLockResult.accepted == List(tokenLockBlock),
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,

        // Delegated stake for the replacement token lock should be rejected.
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        !newSnapshotInfo.activeDelegatedStakes.get.contains(address1)
      )
  }

  test("should handle delegated stake creation and withdrawal in same snapshot") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create token lock
      tokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      hashedTokenLock <- tokenLock.toHashed

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(tokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        updateNodeParameters = existingUpdateNodeParameters.some,
        activeTokenLocks = existingTokenLocks.some
      )

      // Create delegated stake event
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedTokenLock)
      hashedDelegatedStakeEvent <- delegatedStakeEvent.toHashed

      // Create withdrawal event
      withdrawalEvent <- mkDelegatedStakeWithdraw(keyPair, hashedDelegatedStakeEvent)

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List(withdrawalEvent),
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Delegated stake should be created
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        newSnapshotInfo.activeDelegatedStakes.get.contains(address1),
        newSnapshotInfo.activeDelegatedStakes.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedTokenLock.hash
        ).some,
        newSnapshotInfo.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          tokenLock.amount.value.value
        ).some,

        // Withdrawal should not be processed and added to pending withdrawals
        newSnapshotInfo.delegatedStakesWithdrawals.isDefined,
        newSnapshotInfo.delegatedStakesWithdrawals.get.getOrElse(address1, SortedSet.empty[PendingDelegatedStakeWithdrawal]).isEmpty
      )
  }

  test("should handle delegated stake creation in first snapshot and withdrawal in second snapshot") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create token lock
      tokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      hashedTokenLock <- tokenLock.toHashed

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(tokenLock)
      )

      // First snapshot: create the delegated stake
      initialSnapshotInfo = mkGlobalSnapshotInfo(
        updateNodeParameters = existingUpdateNodeParameters.some,
        activeTokenLocks = existingTokenLocks.some
      )

      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedTokenLock)
      hashedDelegatedStakeEvent <- delegatedStakeEvent.toHashed

      manager1 <- mkManager(initialSnapshotInfo.some)
      result1 <- manager1.accept(
        ordinal = SnapshotOrdinal(1L),
        epochProgress = EpochProgress(5L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = initialSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(initialSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, snapshotWithDelegatedStake, _, _, _, _, _) = result1

      // Second snapshot: create the withdrawal
      withdrawalEvent <- mkDelegatedStakeWithdraw(keyPair, hashedDelegatedStakeEvent)

      manager2 <- mkManager(snapshotWithDelegatedStake.some)
      result2 <- manager2.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List(withdrawalEvent),
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = snapshotWithDelegatedStake,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(snapshotWithDelegatedStake),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, finalSnapshotInfo, _, _, _, _, _) = result2
    } yield
      expect.all(
        // First snapshot should have created the delegated stake
        snapshotWithDelegatedStake.activeDelegatedStakes.isDefined,
        snapshotWithDelegatedStake.activeDelegatedStakes.get.contains(address1),
        snapshotWithDelegatedStake.activeDelegatedStakes.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedTokenLock.hash
        ).some,
        snapshotWithDelegatedStake.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          tokenLock.amount.value.value
        ).some,

        // Second snapshot should process the withdrawal and add it to pending withdrawals
        finalSnapshotInfo.delegatedStakesWithdrawals.isDefined,
        finalSnapshotInfo.delegatedStakesWithdrawals.get.contains(address1),
        finalSnapshotInfo.delegatedStakesWithdrawals.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedTokenLock.hash
        ).some,

        // The delegated stake should not be active
        finalSnapshotInfo.activeDelegatedStakes.isDefined,
        !finalSnapshotInfo.activeDelegatedStakes.get.contains(address1)
      )
  }

  test("should handle withdrawal and token lock replacement in same snapshot") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      hashedOriginalTokenLock <- originalTokenLock.toHashed

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(300L),
        replaceTokenLockRef = hashedOriginalTokenLock.hash.some
      )
      hashedReplacementTokenLock <- replacementTokenLock.toHashed

      // Create existing delegated stake in the context
      existingDelegatedStake <- mkDelegatedStakeCreate(keyPair, nodeId, hashedOriginalTokenLock)
      hashedExistingDelegatedStake <- existingDelegatedStake.toHashed

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      existingDelegatedStakes = SortedMap(
        address1 -> SortedSet(
          DelegatedStakeRecord(
            existingDelegatedStake,
            SnapshotOrdinal(1L),
            Amount(5L),
            none,
            none
          )
        )
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        updateNodeParameters = existingUpdateNodeParameters.some,
        activeTokenLocks = existingTokenLocks.some,
        activeDelegatedStakes = existingDelegatedStakes.some
      )

      // Create withdrawal event for the existing delegated stake
      withdrawalEvent <- mkDelegatedStakeWithdraw(keyPair, hashedExistingDelegatedStake)

      // Create token lock block for the replacement
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List(withdrawalEvent),
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock should be replaced
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,

        // Withdrawal should be rejected
        newSnapshotInfo.delegatedStakesWithdrawals.isDefined,
        !newSnapshotInfo.delegatedStakesWithdrawals.get.contains(address1),

        // The delegated stake should still be active (not removed immediately)
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        newSnapshotInfo.activeDelegatedStakes.get.contains(address1)
      )
  }

  test("should handle delegated stake creation, withdrawal, and token lock replacement in same snapshot") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      hashedOriginalTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(300L),
        replaceTokenLockRef = hashedOriginalTokenLock.hash.some
      )
      hashedReplacementTokenLock <- replacementTokenLock.toHashed

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        updateNodeParameters = existingUpdateNodeParameters.some,
        activeTokenLocks = existingTokenLocks.some
      )

      // Create delegated stake event for the original token lock
      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedOriginalTokenLock)
      hashedDelegatedStakeEvent <- delegatedStakeEvent.toHashed

      // Create withdrawal event for the delegated stake
      withdrawalEvent <- mkDelegatedStakeWithdraw(keyPair, hashedDelegatedStakeEvent)

      // Create token lock block for the replacement
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List(withdrawalEvent),
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should succeed
        tokenLockResult.accepted == List(tokenLockBlock),
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,

        // Delegated stake should be rejected
        newSnapshotInfo.activeDelegatedStakes.isDefined,
        !newSnapshotInfo.activeDelegatedStakes.get.contains(address1),

        // Withdrawal should be rejected
        newSnapshotInfo.delegatedStakesWithdrawals.isDefined,
        !newSnapshotInfo.delegatedStakesWithdrawals.get.contains(address1)
      )
  }

  test("a due withdrawal rolls from a replaced token lock at R and settles exactly once at R+1") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      hashedOriginalTokenLock <- originalTokenLock.toHashed

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      // First snapshot: create the delegated stake
      initialSnapshotInfo = mkGlobalSnapshotInfo(
        updateNodeParameters = existingUpdateNodeParameters.some,
        activeTokenLocks = existingTokenLocks.some
      )

      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedOriginalTokenLock)
      hashedDelegatedStakeEvent <- delegatedStakeEvent.toHashed

      manager <- mkManager(initialSnapshotInfo.some, SnapshotOrdinal.MinValue)
      result1 <- manager.accept(
        ordinal = SnapshotOrdinal(1L),
        epochProgress = EpochProgress(5L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = initialSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(initialSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, snapshotWithDelegatedStake, _, _, _, _, _) = result1

      // Second snapshot: create the withdrawal
      withdrawalEvent <- mkDelegatedStakeWithdraw(keyPair, hashedDelegatedStakeEvent)

      result2 <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List(withdrawalEvent),
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = snapshotWithDelegatedStake,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(snapshotWithDelegatedStake),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, snapshotWithWithdrawal, _, _, _, _, _) = result2

      // At R the withdrawal is already due, while the still-active original token lock is replaced.
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(300L),
        replaceTokenLockRef = hashedOriginalTokenLock.hash.some
      )
      hashedReplacementTokenLock <- replacementTokenLock.toHashed

      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      result3 <- manager
        .accept(
          ordinal = SnapshotOrdinal(3L),
          epochProgress = EpochProgress(15L),
          blocksForAcceptance = List.empty,
          allowSpendBlocksForAcceptance = List.empty,
          tokenLockBlocksForAcceptance = List(tokenLockBlock),
          scEvents = List.empty,
          unpEvents = List.empty,
          cdsEvents = List.empty,
          wdsEvents = List.empty,
          cncEvents = List.empty,
          wncEvents = List.empty,
          lastSnapshotContext = snapshotWithWithdrawal,
          lastActiveTips = SortedSet.empty,
          lastDeprecatedTips = SortedSet.empty,
          calculateRewardsFn = delegatedRewardsFunction(snapshotWithWithdrawal),
          validationType = StateChannelValidationType.Full,
          getGlobalSnapshotByOrdinal = _ => None.pure[IO],
          allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
        )
      (_, _, tokenLockResultAtR, _, _, _, _, _, snapshotAtR, _, _, _, artifactsAtR, _) = result3
      unlocksAtR = artifactsAtR.collect { case unlock: TokenUnlock => unlock }

      // At R+1 the rewritten NEW reference is now in the last-active set, so the carried withdrawal can settle.
      result4 <- manager.accept(
        ordinal = SnapshotOrdinal(4L),
        epochProgress = EpochProgress(16L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = snapshotAtR,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(snapshotAtR),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )
      (_, _, _, _, _, _, _, _, snapshotAtRPlus1, _, _, _, artifactsAtRPlus1, _) = result4
      unlocksAtRPlus1 = artifactsAtRPlus1.collect { case unlock: TokenUnlock => unlock }
    } yield
      expect.all(
        // First snapshot should have created the delegated stake
        snapshotWithDelegatedStake.activeDelegatedStakes.isDefined,
        snapshotWithDelegatedStake.activeDelegatedStakes.get.contains(address1),
        snapshotWithDelegatedStake.activeDelegatedStakes.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedOriginalTokenLock.hash
        ).some,
        snapshotWithDelegatedStake.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          originalTokenLock.amount.value.value
        ).some,
        snapshotWithDelegatedStake.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,

        // Second snapshot should process the withdrawal and add it to pending withdrawals
        snapshotWithWithdrawal.delegatedStakesWithdrawals.isDefined,
        snapshotWithWithdrawal.delegatedStakesWithdrawals.get.contains(address1),
        snapshotWithWithdrawal.delegatedStakesWithdrawals.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedOriginalTokenLock.hash
        ).some,

        // The delegated stake should not be active after withdrawal
        !snapshotWithWithdrawal.activeDelegatedStakes.get.contains(address1),
        snapshotWithWithdrawal.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,

        // R unlocks OLD, installs NEW, and carries the already-due withdrawal under NEW for one round.
        tokenLockResultAtR.accepted == List(tokenLockBlock),
        unlocksAtR.toList.map(_.tokenLockRef) == List(hashedOriginalTokenLock.hash),
        artifactsAtR.size == 1,
        snapshotAtR.delegatedStakesWithdrawals.isDefined,
        snapshotAtR.delegatedStakesWithdrawals.get.contains(address1),
        snapshotAtR.delegatedStakesWithdrawals.get.get(address1).map(_.toList.map(_.tokenLockRef)) == List(
          hashedReplacementTokenLock.hash
        ).some,
        snapshotAtR.delegatedStakesWithdrawals.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          replacementTokenLock.amount.value.value
        ).some,
        !snapshotAtR.activeDelegatedStakes.get.contains(address1),
        snapshotAtR.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,
        snapshotAtR.balances(address1).value.value ==
          snapshotWithWithdrawal
            .balances(address1)
            .value
            .value + originalTokenLock.amount.value.value - replacementTokenLock.amount.value.value,

        // R+1 emits only NEW, credits its full principal, removes the lock, and cleans the pending withdrawal.
        unlocksAtRPlus1.toList.map(_.tokenLockRef) == List(hashedReplacementTokenLock.hash),
        artifactsAtRPlus1.size == 1,
        snapshotAtRPlus1.balances(address1).value.value ==
          snapshotAtR.balances(address1).value.value + replacementTokenLock.amount.value.value,
        !snapshotAtRPlus1.activeTokenLocks.get.contains(address1),
        !snapshotAtRPlus1.delegatedStakesWithdrawals.get.contains(address1),
        !snapshotAtRPlus1.activeDelegatedStakes.get.contains(address1)
      )
  }

  test("should handle delegated stake creation in first snapshot, token lock replacement in second, and withdrawal in third") { res =>
    implicit val (h, sp) = res

    for {
      nodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      nodeId = nodeKeyPair.getPublic.toId.toPeerId
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = none
      )
      hashedOriginalTokenLock <- originalTokenLock.toHashed

      // Create UpdateNodeParameters for the nodeId
      updateNodeParams <- mkUpdateNodeParameters(
        nodeKeyPair,
        nodeId,
        rewardFraction = 7_000_000
      )

      // Add UpdateNodeParameters to the context
      existingUpdateNodeParameters = SortedMap(
        updateNodeParams.proofs.head.id -> (updateNodeParams, SnapshotOrdinal(NonNegLong(1L)))
      )

      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      // First snapshot: create the delegated stake
      initialSnapshotInfo = mkGlobalSnapshotInfo(
        updateNodeParameters = existingUpdateNodeParameters.some,
        activeTokenLocks = existingTokenLocks.some
      )

      delegatedStakeEvent <- mkDelegatedStakeCreate(keyPair, nodeId, hashedOriginalTokenLock)
      hashedDelegatedStakeEvent <- delegatedStakeEvent.toHashed

      manager <- mkManager(initialSnapshotInfo.some)
      result1 <- manager.accept(
        ordinal = SnapshotOrdinal(1L),
        epochProgress = EpochProgress(5L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List(delegatedStakeEvent),
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = initialSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(initialSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, snapshotWithDelegatedStake, _, _, _, _, _) = result1

      // Second snapshot: replace the token lock
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(300L),
        replaceTokenLockRef = hashedOriginalTokenLock.hash.some
      )
      hashedReplacementTokenLock <- replacementTokenLock.toHashed

      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      result2 <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = snapshotWithDelegatedStake,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(snapshotWithDelegatedStake),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, snapshotWithReplacement, _, _, _, _, _) = result2

      // Third snapshot: withdraw the delegated stake
      withdrawalEvent <- mkDelegatedStakeWithdraw(keyPair, hashedDelegatedStakeEvent)

      result3 <- manager.accept(
        ordinal = SnapshotOrdinal(3L),
        epochProgress = EpochProgress(15L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List(withdrawalEvent),
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = snapshotWithReplacement,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(snapshotWithReplacement),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, snapshotWithWithdrawal, _, _, _, _, _) = result3

      // Fourth snapshot: test withdrawal expiration (advance epoch significantly)
      result4 <- manager.accept(
        ordinal = SnapshotOrdinal(4L),
        epochProgress = EpochProgress(25L), // Advance epoch significantly to trigger expiration
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = snapshotWithWithdrawal,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(snapshotWithWithdrawal),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, _, _, _, _, _, _, finalSnapshotInfo, _, _, _, _, _) = result4
    } yield
      expect.all(
        // First snapshot should have created the delegated stake
        snapshotWithDelegatedStake.activeDelegatedStakes.isDefined,
        snapshotWithDelegatedStake.activeDelegatedStakes.get.contains(address1),
        snapshotWithDelegatedStake.activeDelegatedStakes.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedOriginalTokenLock.hash
        ).some,
        snapshotWithDelegatedStake.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          originalTokenLock.amount.value.value
        ).some,
        snapshotWithDelegatedStake.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,

        // Second snapshot should replace the token lock and update the delegated stake
        snapshotWithReplacement.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,
        snapshotWithReplacement.activeDelegatedStakes.isDefined,
        snapshotWithReplacement.activeDelegatedStakes.get.contains(address1),
        snapshotWithReplacement.activeDelegatedStakes.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedReplacementTokenLock.hash
        ).some,
        snapshotWithReplacement.activeDelegatedStakes.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          replacementTokenLock.amount.value.value
        ).some,

        // Third snapshot should process the withdrawal and add it to pending withdrawals
        snapshotWithWithdrawal.delegatedStakesWithdrawals.isDefined,
        snapshotWithWithdrawal.delegatedStakesWithdrawals.get.contains(address1),
        snapshotWithWithdrawal.delegatedStakesWithdrawals.get.get(address1).map(_.map(_.tokenLockRef)) == SortedSet(
          hashedReplacementTokenLock.hash
        ).some,
        snapshotWithWithdrawal.delegatedStakesWithdrawals.get.get(address1).map(_.toList.map(_.amount.value.value)) == List(
          replacementTokenLock.amount.value.value
        ).some,

        // The delegated stake should not be active after withdrawal
        snapshotWithWithdrawal.activeDelegatedStakes.isDefined,
        !snapshotWithWithdrawal.activeDelegatedStakes.get.contains(address1),

        // The token lock should still be active (replacement token lock)
        snapshotWithWithdrawal.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,

        // Fourth snapshot should expire the withdrawal due to epoch advancement
        finalSnapshotInfo.delegatedStakesWithdrawals.isDefined,
        !finalSnapshotInfo.delegatedStakesWithdrawals.get.contains(address1), // Withdrawal should be expired and removed

        // The delegated stake should still not be active
        finalSnapshotInfo.activeDelegatedStakes.isDefined,
        !finalSnapshotInfo.activeDelegatedStakes.get.contains(address1),

        // The replacement token lock should not be active
        !finalSnapshotInfo.activeTokenLocks.get.contains(address1)
      )
  }

  test("should reject token lock replacement with amount too low") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(30L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock with lower amount (should be rejected)
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(20L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be rejected due to lower amount
        // Original token lock should still be active
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should reject token lock replacement with amount equal to existing") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(30L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock with equal amount (should be rejected)
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(30L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be rejected due to equal amount
        // Original token lock should still be active
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should reject token lock replacement when reference not found in transactions") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(20L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(30L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      anotherTokenLock <- mkTokenLock(
        keyPair1,
        TokenLockAmount(100L),
        replaceTokenLockRef = none
      )
      anotherHashedTokenLock <- anotherTokenLock.toHashed

      // No originalTokenLock provided, so replacement reference won't be found
      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = SortedMap(address1 -> SortedSet(anotherTokenLock)).some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be rejected because reference not found
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.nonEmpty,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(anotherTokenLock).some,
        !newSnapshotInfo.activeTokenLocks.get.contains(address),
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should reject token lock replacement when reference not found in empty transactions") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(20L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(30L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Empty sourceTokenLocks
      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = SortedMap.empty[Address, SortedSet[Signed[TokenLock]]].some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be rejected because reference not found in empty transactions
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.isEmpty,
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should reject token lock replacement with correct reference but wrong source address") { res =>
    implicit val (h, sp) = res

    for {
      originalKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      differentKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = originalKeyPair.getPublic.toAddress
      address2 = differentKeyPair.getPublic.toAddress

      // Create original token lock with original key pair
      originalTokenLock <- mkTokenLock(
        originalKeyPair,
        TokenLockAmount(20L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Try to replace with a different key pair (different source address)
      replacementTokenLock <- mkTokenLock(
        differentKeyPair,
        TokenLockAmount(30L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      )

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be rejected because source address doesn't match
        // Original token lock should still be active
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,
        !newSnapshotInfo.activeTokenLocks.get.contains(address2),
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should accept one token lock replacement out of multiple valid blocks") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(100L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create multiple valid replacement token locks (all with higher amounts)
      replacementTokenLock1 <- mkTokenLock(
        keyPair,
        TokenLockAmount(150L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )
      replacementTokenLock2 <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )
      replacementTokenLock3 <- mkTokenLock(
        keyPair,
        TokenLockAmount(250L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      )

      // Create token lock blocks with all replacement token locks
      tokenLockBlock1 <- mkTokenLockBlock(List(replacementTokenLock1))
      tokenLockBlock2 <- mkTokenLockBlock(List(replacementTokenLock2))
      tokenLockBlock3 <- mkTokenLockBlock(List(replacementTokenLock3))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock1, tokenLockBlock2, tokenLockBlock3),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
      // Determine which replacement was accepted
      acceptedReplacements = newSnapshotInfo.activeTokenLocks.get.getOrElse(address1, SortedSet.empty[Signed[TokenLock]])
      acceptedReplacement = acceptedReplacements.headOption
    } yield
      expect.all(
        // Exactly one replacement should be accepted
        acceptedReplacements.size == 1,
        acceptedReplacement.isDefined,
        // The accepted replacement should have a higher amount than the original
        acceptedReplacement.exists(_.value.amount.value.value > originalTokenLock.amount.value.value),
        // The original token lock should be replaced by exactly one replacement
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.contains(address1),
        newSnapshotInfo.activeTokenLocks.get.get(address1).map(_.size) == 1.some,
        // The replacement in activeTokenLocks should match one of the submitted replacements
        newSnapshotInfo.activeTokenLocks.get.get(address1).exists { locks =>
          locks.exists(lock =>
            lock.value.amount.value.value > originalTokenLock.amount.value.value &&
              lock.value.replaceTokenLockRef.contains(originalHashedTokenLock.hash)
          )
        },
        // The original token lock should not be in activeTokenLocks
        !newSnapshotInfo.activeTokenLocks.get.get(address1).exists(_.contains(originalTokenLock)),
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should accept one token lock replacement out of multiple valid replacements") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(100L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create multiple valid replacement token locks (all with higher amounts)
      replacementTokenLock1 <- mkTokenLock(
        keyPair,
        TokenLockAmount(150L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )
      replacementTokenLock2 <- mkTokenLock(
        keyPair,
        TokenLockAmount(200L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )
      replacementTokenLock3 <- mkTokenLock(
        keyPair,
        TokenLockAmount(250L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      )

      // Create token lock blocks with all replacement token locks
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock1, replacementTokenLock2, replacementTokenLock3))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
      // Determine which replacement was accepted
      acceptedReplacements = newSnapshotInfo.activeTokenLocks.get.getOrElse(address1, SortedSet.empty[Signed[TokenLock]])
      acceptedReplacement = acceptedReplacements.headOption
    } yield
      expect.all(
        // Exactly one replacement should be accepted
        acceptedReplacements.size == 1,
        acceptedReplacement.isDefined,
        // The accepted replacement should have a higher amount than the original
        acceptedReplacement.exists(_.value.amount.value.value > originalTokenLock.amount.value.value),
        // The original token lock should be replaced by exactly one replacement
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.contains(address1),
        newSnapshotInfo.activeTokenLocks.get.get(address1).map(_.size) == 1.some,
        // The replacement in activeTokenLocks should match one of the submitted replacements
        newSnapshotInfo.activeTokenLocks.get.get(address1).exists { locks =>
          locks.exists(lock =>
            lock.value.amount.value.value > originalTokenLock.amount.value.value &&
              lock.value.replaceTokenLockRef.contains(originalHashedTokenLock.hash)
          )
        },
        // The original token lock should not be in activeTokenLocks
        !newSnapshotInfo.activeTokenLocks.get.get(address1).exists(_.contains(originalTokenLock)),
        // but the token lock block should be accepted
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should reject token lock replacement with correct reference but insufficient balance") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock with amount 50L and fee 10L (total 60L locked)
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(50L),
        fee = TokenLockFee(10L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock with amount 60L and fee 5L (total 65L needed)
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(60L),
        fee = TokenLockFee(5L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      // Set balance to 4L, which is insufficient
      // 10L + 50L < 65L, so should be rejected
      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      ).copy(balances = SortedMap(address1 -> Balance(10L)))

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be rejected due to insufficient balance
        // Original token lock should still be active
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(originalTokenLock).some,
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }

  test("should accept token lock replacement with correct reference and sufficient balance") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair.getPublic.toAddress

      // Create original token lock with amount 50L and fee 10L (total 60L locked)
      originalTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(50L),
        fee = TokenLockFee(10L),
        replaceTokenLockRef = none
      )
      originalHashedTokenLock <- originalTokenLock.toHashed

      // Create replacement token lock with amount 60L and fee 5L (total 65L needed)
      replacementTokenLock <- mkTokenLock(
        keyPair,
        TokenLockAmount(60L),
        fee = TokenLockFee(5L),
        replaceTokenLockRef = originalHashedTokenLock.hash.some
      )

      // Setup context with existing token lock
      existingTokenLocks = SortedMap(
        address1 -> SortedSet(originalTokenLock)
      )

      // Set balance to 10L, which is sufficient
      // 20L + 50L >= 65L, so should be accepted
      lastSnapshotInfo = mkGlobalSnapshotInfo(
        activeTokenLocks = existingTokenLocks.some
      ).copy(balances = SortedMap(address1 -> Balance(15L)))

      // Create token lock block with replacement token lock
      tokenLockBlock <- mkTokenLockBlock(List(replacementTokenLock))

      manager <- mkManager(lastSnapshotInfo.some)
      result <- manager.accept(
        ordinal = SnapshotOrdinal(2L),
        epochProgress = EpochProgress(10L),
        blocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List(tokenLockBlock),
        scEvents = List.empty,
        unpEvents = List.empty,
        cdsEvents = List.empty,
        wdsEvents = List.empty,
        cncEvents = List.empty,
        wncEvents = List.empty,
        lastSnapshotContext = lastSnapshotInfo,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = delegatedRewardsFunction(lastSnapshotInfo),
        validationType = StateChannelValidationType.Full,
        getGlobalSnapshotByOrdinal = _ => None.pure[IO],
        allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
      )

      (_, _, tokenLockResult, _, _, _, _, _, newSnapshotInfo, _, _, _, _, _) = result
    } yield
      expect.all(
        // Token lock replacement should be accepted
        newSnapshotInfo.activeTokenLocks.isDefined,
        newSnapshotInfo.activeTokenLocks.get.get(address1) == SortedSet(replacementTokenLock).some,
        tokenLockResult.accepted.nonEmpty,
        tokenLockResult.notAccepted.isEmpty
      )
  }
}
