package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DelegatedRewardsDistributorSuite extends SimpleIOSuite with Checkers {

  val address1 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  val address2 = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")
  val address3 = Address("DAG5bvqxSJmbWVwcKWEU7nb3sgTnN1QZMPi4F8Cc")
  val nodeId1 = Id(Hex("1234567890abcdef")).toPeerId
  val nodeId2 = Id(Hex("abcdef1234567890")).toPeerId

  def createSignedStake(
    source: Address,
    nodeId: PeerId,
    amount: Long,
    tokenLockRef: Hash = Hash.empty
  ): Signed[UpdateDelegatedStake.Create] =
    Signed(
      UpdateDelegatedStake.Create(
        source = source,
        nodeId = nodeId,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount)),
        fee = DelegatedStakeFee(0L),
        tokenLockRef = tokenLockRef
      ),
      NonEmptySet.one[SignatureProof](SignatureProof(nodeId.toId, Signature(Hex(Hash.empty.value))))
    )

  test("getUpdatedCreateDelegatedStakes should preserve balance and skip rewards issuance when updating existing delegation records") {
    val delegatorRewardsMap = Map(
      nodeId1 -> Map(address1 -> Amount(100L)),
      nodeId2 -> Map(address2 -> Amount(200L))
    )

    val stake1A = createSignedStake(address1, nodeId1, 1000L)
    val stake1B = createSignedStake(address1, nodeId2, 1000L)
    val stake2 = createSignedStake(address2, nodeId2, 2000L)
    val stake3 = createSignedStake(address3, nodeId1, 3000L)

    val existingStakes = SortedMap(
      address1 -> SortedSet(
        DelegatedStakeRecord(stake1A, SnapshotOrdinal(1L), Balance(50L))
      ),
      address2 -> SortedSet(
        DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), Balance(75L))
      )
    )

    val delegatedStakeDiffs = UpdateDelegatedStakeAcceptanceResult(
      acceptedCreates = SortedMap(
        address1 -> List((stake1B, SnapshotOrdinal(2L))),
        address2 -> List((stake2, SnapshotOrdinal(2L))),
        address3 -> List((stake3, SnapshotOrdinal(2L)))
      ),
      notAcceptedCreates = List.empty,
      acceptedWithdrawals = SortedMap.empty,
      notAcceptedWithdrawals = List.empty
    )

    val partitionedRecords = PartitionedStakeUpdates(
      unexpiredCreateDelegatedStakes = existingStakes,
      unexpiredWithdrawalsDelegatedStaking = SortedMap.empty,
      expiredWithdrawalsDelegatedStaking = SortedMap.empty
    )

    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]

      updatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](
        delegatorRewardsMap,
        delegatedStakeDiffs,
        partitionedRecords
      )

    } yield {
      val address1Balance = updatedStakes.get(address1).map(_.map(_.rewards.value.value).sum).getOrElse(0L)
      val address2Balance = updatedStakes.get(address2).map(_.map(_.rewards.value.value).sum).getOrElse(0L)
      val address3Balance = updatedStakes.get(address3).map(_.map(_.rewards.value.value).sum).getOrElse(0L)

      // Address1 balance should be just the existing 50L (no new rewards since it's being modified)
      expect(address1Balance === 50L) &&
      // Address2 balance should be 75L (no new rewards)
      expect(address2Balance === 75L) &&
      // Address3 balance should still be 0L as it's a new stake
      expect(address3Balance === 0L)
    }
  }

  test("getUpdatedCreateDelegatedStakes should update existing balances with new rewards") {
    val delegatorRewardsMap = Map(
      nodeId1 -> Map(
        address1 -> Amount(50L)
      ),
      nodeId2 -> Map(
        address2 -> Amount(75L)
      )
    )

    val stake1 = createSignedStake(address1, nodeId1, 1000L)
    val stake2 = createSignedStake(address2, nodeId2, 2000L)

    val existingStakes = SortedMap(
      address1 -> SortedSet(
        DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), Balance(100L))
      ),
      address2 -> SortedSet(
        DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), Balance(200L))
      )
    )

    val delegatedStakeDiffs = UpdateDelegatedStakeAcceptanceResult(
      acceptedCreates = SortedMap.empty,
      notAcceptedCreates = List.empty,
      acceptedWithdrawals = SortedMap.empty,
      notAcceptedWithdrawals = List.empty
    )

    val partitionedRecords = PartitionedStakeUpdates(
      unexpiredCreateDelegatedStakes = existingStakes,
      unexpiredWithdrawalsDelegatedStaking = SortedMap.empty,
      expiredWithdrawalsDelegatedStaking = SortedMap.empty
    )

    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]

      updatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](
        delegatorRewardsMap,
        delegatedStakeDiffs,
        partitionedRecords
      )
    } yield {
      val address1Balance = updatedStakes.get(address1).flatMap(_.headOption.map(_.rewards.value.value))
      val address2Balance = updatedStakes.get(address2).flatMap(_.headOption.map(_.rewards.value.value))

      expect(address1Balance.contains(150L)) &&
      expect(address2Balance.contains(275L))
    }
  }

  test("getUpdatedCreateDelegatedStakes should handle withdrawals correctly") {
    val stake1 = createSignedStake(address1, nodeId1, 1000L)
    val stake2 = createSignedStake(address2, nodeId2, 2000L)

    val withdrawal1 = Signed(
      UpdateDelegatedStake.Withdraw(
        source = address1,
        stakeRef = Hash.empty
      ),
      NonEmptySet.one[SignatureProof](SignatureProof(nodeId1.toId, Signature(Hex(Hash.empty.value))))
    )

    val existingStakes = SortedMap(
      address1 -> SortedSet(
        DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), Balance(100L))
      ),
      address2 -> SortedSet(
        DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), Balance(200L))
      )
    )

    val delegatorRewardsMap = Map(
      nodeId1 -> Map(address1 -> Amount(50L)),
      nodeId2 -> Map(address2 -> Amount(75L))
    )

    val delegatedStakeDiffs = UpdateDelegatedStakeAcceptanceResult(
      acceptedCreates = SortedMap.empty,
      notAcceptedCreates = List.empty,
      acceptedWithdrawals = SortedMap(
        address1 -> List((withdrawal1, EpochProgress(1L)))
      ),
      notAcceptedWithdrawals = List.empty
    )

    val partitionedRecords = PartitionedStakeUpdates(
      unexpiredCreateDelegatedStakes = existingStakes,
      unexpiredWithdrawalsDelegatedStaking = SortedMap.empty,
      expiredWithdrawalsDelegatedStaking = SortedMap.empty
    )

    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]

      stakeRef <- DelegatedStakeReference.of[IO](stake1)
      updatedWithdrawal = Signed(
        UpdateDelegatedStake.Withdraw(
          source = address1,
          stakeRef = stakeRef.hash
        ),
        withdrawal1.proofs
      )

      updatedDiffs = UpdateDelegatedStakeAcceptanceResult(
        acceptedCreates = SortedMap.empty,
        notAcceptedCreates = List.empty,
        acceptedWithdrawals = SortedMap(
          address1 -> List((updatedWithdrawal, EpochProgress(1L)))
        ),
        notAcceptedWithdrawals = List.empty
      )

      updatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](
        delegatorRewardsMap,
        updatedDiffs,
        partitionedRecords
      )
    } yield {
      val address1Stakes = updatedStakes.get(address1).map(_.toList.length)
      val address2Balance = updatedStakes.get(address2).flatMap(_.headOption.map(_.rewards.value.value))

      expect(address1Stakes.isEmpty || address1Stakes.contains(0))
        .and(expect(address2Balance.contains(275L)))
    }
  }

  test("modified stakes should receive rewards in subsequent rounds") {
    val firstRoundRewardsMap = Map(
      nodeId1 -> Map(address1 -> Amount(100L)),
      nodeId2 -> Map(address2 -> Amount(200L))
    )

    val customTokenLockRef = Hash("1234567890abcdef1234567890abcdef")
    val stake1A = createSignedStake(address1, nodeId1, 1000L, customTokenLockRef)
    val stake1B = createSignedStake(address1, nodeId2, 1000L, customTokenLockRef)

    val initialStakes = SortedMap(
      address1 -> SortedSet(
        DelegatedStakeRecord(stake1A, SnapshotOrdinal(1L), Balance(50L))
      )
    )

    val firstRoundDiffs = UpdateDelegatedStakeAcceptanceResult(
      acceptedCreates = SortedMap(
        address1 -> List((stake1B, SnapshotOrdinal(2L)))
      ),
      notAcceptedCreates = List.empty,
      acceptedWithdrawals = SortedMap.empty,
      notAcceptedWithdrawals = List.empty
    )

    val firstRoundPartitioned = PartitionedStakeUpdates(
      unexpiredCreateDelegatedStakes = initialStakes,
      unexpiredWithdrawalsDelegatedStaking = SortedMap.empty,
      expiredWithdrawalsDelegatedStaking = SortedMap.empty
    )

    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]

      // Process first round - stake is modified, should preserve existing rewards but not add new ones
      firstRoundResult <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](
        firstRoundRewardsMap,
        firstRoundDiffs,
        firstRoundPartitioned
      )

      // Verify first round: balance should be preserved but no new rewards
      firstRoundBalance = firstRoundResult.get(address1).flatMap(_.headOption.map(_.rewards.value.value))
      _ = expect(firstRoundBalance.contains(50L)) // Only the initial 50L, no new rewards

      // SECOND ROUND - Previously modified stake should now receive rewards
      secondRoundRewardsMap = Map(
        nodeId2 -> Map(address1 -> Amount(150L)) // Now rewards go to nodeId2 for address1
      )

      secondRoundDiffs = UpdateDelegatedStakeAcceptanceResult(
        acceptedCreates = SortedMap.empty,
        notAcceptedCreates = List.empty,
        acceptedWithdrawals = SortedMap.empty,
        notAcceptedWithdrawals = List.empty
      )

      secondRoundPartitioned = PartitionedStakeUpdates(
        unexpiredCreateDelegatedStakes = firstRoundResult,
        unexpiredWithdrawalsDelegatedStaking = SortedMap.empty,
        expiredWithdrawalsDelegatedStaking = SortedMap.empty
      )

      secondRoundResult <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](
        secondRoundRewardsMap,
        secondRoundDiffs,
        secondRoundPartitioned
      )

      // Verify second round: balance should now include new rewards
      secondRoundBalance = secondRoundResult.get(address1).flatMap(_.headOption.map(_.rewards.value.value))
    } yield
      // In second round, should have original 50L + 150L = 200L
      expect(secondRoundBalance.contains(200L))
  }

  test("identifyModifiedStakes should correctly identify stakes based on comparison of tokenLockRef") {

    for {
      walletAddress <- Address("DAG4BDGAgF4sXweHbVVKuY5ajYekCYZwb58mgRtq").pure[IO]
      nodeId1 = Id(
        Hex(
          "2daaac8567338a220852a1ecd70ad6959acb2619a9950d738922504fc04506c3e1adeff85a06bf64201c7550e9fe3ec21878c8a9d75b0a4ed4d7c3eee54e12dc"
        )
      ).toPeerId
      nodeId2 = Id(
        Hex(
          "73b31edfa6b163e525cb83683f6500739bebe21237fcfae33cf63e5f65d36ed6a43425555708fd44741c702b8727aa668d238e3fed343b905a9a417287ab56f5"
        )
      ).toPeerId

      // Create a tokenLockRef hash that matches the one in the logs
      tokenLockRefString = "f2f9c4569fa3fe81ee7fa7898579ea9823b26cc1bf33d0370df7e099f4828429"
      tokenLockRef = Hash(tokenLockRefString)

      // Create an initial stake with the first nodeId
      originalStake = createSignedStake(walletAddress, nodeId1, 1000L, tokenLockRef)

      // Create a modified stake with the second nodeId but same tokenLockRef
      modifiedStake = createSignedStake(walletAddress, nodeId2, 1000L, tokenLockRef)

      // Set up existing records and accepted creates
      existingRecords = SortedMap(
        walletAddress -> SortedSet(
          DelegatedStakeRecord(originalStake, SnapshotOrdinal(10L), Balance(103778187663683L))
        )
      )

      acceptedCreates = SortedMap(
        walletAddress -> List((modifiedStake, SnapshotOrdinal.unsafeApply(11L)))
      )

      // Identify modified stakes
      modifiedStakes = DelegatedRewardsDistributor.identifyModifiedStakes(existingRecords, acceptedCreates)

      // Check if the stake is correctly identified as modified
    } yield
      expect(modifiedStakes.contains((walletAddress, tokenLockRef))) &&
        expect(modifiedStakes.size === 1)
  }

  test("Stake modification correctly preserves balance with unexpiredCreateDelegatedStakes") {
    val walletAddress = Address("DAG4BDGAgF4sXweHbVVKuY5ajYekCYZwb58mgRtq")
    val tokenLockRefString = "f2f9c4569fa3fe81ee7fa7898579ea9823b26cc1bf33d0370df7e099f4828429"
    val nodeId1 = Id(
      Hex(
        "2daaac8567338a220852a1ecd70ad6959acb2619a9950d738922504fc04506c3e1adeff85a06bf64201c7550e9fe3ec21878c8a9d75b0a4ed4d7c3eee54e12dc"
      )
    ).toPeerId
    val nodeId2 = Id(
      Hex(
        "73b31edfa6b163e525cb83683f6500739bebe21237fcfae33cf63e5f65d36ed6a43425555708fd44741c702b8727aa668d238e3fed343b905a9a417287ab56f5"
      )
    ).toPeerId

    def createSignedStake(
      source: Address,
      nodeId: PeerId,
      amount: Long,
      tokenLockRef: Hash
    ): Signed[UpdateDelegatedStake.Create] =
      Signed(
        UpdateDelegatedStake.Create(
          source = source,
          nodeId = nodeId,
          amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount)),
          fee = DelegatedStakeFee(0L),
          tokenLockRef = tokenLockRef
        ),
        NonEmptySet.one[SignatureProof](SignatureProof(nodeId.toId, Signature(Hex(Hash.empty.value))))
      )

    // Simulate a basic DelegatedRewardsResult response for testing
    def mockRewardsFn(input: RewardsInput): IO[DelegatedRewardsResult] =
      input match {
        case DelegateRewardsInput(delegatedStakeAcceptanceResult, partitionedRecords, _, _) =>
          // Simple calculation: add 100L to each existing balance for unmodified stakes
          for {
            implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
            implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]

            modifiedStakes = DelegatedRewardsDistributor.identifyModifiedStakes(
              partitionedRecords.unexpiredCreateDelegatedStakes,
              delegatedStakeAcceptanceResult.acceptedCreates
            )

            rewardsMap = partitionedRecords.unexpiredCreateDelegatedStakes.flatMap {
              case (addr, records) =>
                records.toList.map { record =>
                  val isModified = modifiedStakes.contains((addr, record.event.value.tokenLockRef))
                  val nodeId = record.event.value.nodeId
                  val rewardAmount = if (!isModified) Amount(100L) else Amount(0L)
                  (nodeId, addr -> rewardAmount)
                }
            }.groupBy(_._1).view.mapValues(pairs => pairs.values.toMap).toMap

            updatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](
              rewardsMap,
              delegatedStakeAcceptanceResult,
              partitionedRecords
            )

          } yield
            DelegatedRewardsResult(
              delegatorRewardsMap = SortedMap.from(rewardsMap),
              updatedCreateDelegatedStakes = updatedStakes,
              updatedWithdrawDelegatedStakes = SortedMap.empty,
              nodeOperatorRewards = SortedSet.empty,
              reservedAddressRewards = SortedSet.empty,
              withdrawalRewardTxs = SortedSet.empty,
              totalEmittedRewardsAmount = Amount(100L)
            )

        case _ =>
          IO.pure(
            DelegatedRewardsResult(
              delegatorRewardsMap = SortedMap.empty,
              updatedCreateDelegatedStakes = SortedMap.empty,
              updatedWithdrawDelegatedStakes = SortedMap.empty,
              nodeOperatorRewards = SortedSet.empty,
              reservedAddressRewards = SortedSet.empty,
              withdrawalRewardTxs = SortedSet.empty,
              totalEmittedRewardsAmount = Amount(0L)
            )
          )
      }

    val originalTokenLockRef = Hash(tokenLockRefString)
    val initialBalance = 103778187663683L
    val originalStake = createSignedStake(walletAddress, nodeId1, 1000L, originalTokenLockRef)
    val modifiedStake = createSignedStake(walletAddress, nodeId2, 1000L, originalTokenLockRef)
    val balanceValue = NonNegLong.unsafeFrom(initialBalance)
    val unexpiredCreateDelegatedStakes = SortedMap(
      walletAddress -> SortedSet(
        DelegatedStakeRecord(originalStake, SnapshotOrdinal(10L), Balance(balanceValue))
      )
    )

    val delegatedStakeAcceptanceResult = UpdateDelegatedStakeAcceptanceResult(
      acceptedCreates = SortedMap(
        walletAddress -> List((modifiedStake, SnapshotOrdinal(11L)))
      ),
      notAcceptedCreates = List.empty,
      acceptedWithdrawals = SortedMap.empty,
      notAcceptedWithdrawals = List.empty
    )

    val partitionedRecords = PartitionedStakeUpdates(
      unexpiredCreateDelegatedStakes = unexpiredCreateDelegatedStakes,
      unexpiredWithdrawalsDelegatedStaking = SortedMap.empty,
      expiredWithdrawalsDelegatedStaking = SortedMap.empty
    )

    for {
      result <- mockRewardsFn(
        DelegateRewardsInput(delegatedStakeAcceptanceResult, partitionedRecords, EpochProgress(1L), SnapshotOrdinal(1L))
      )

      balance = result.updatedCreateDelegatedStakes.get(walletAddress).flatMap(_.headOption.map(_.rewards.value.value))

      modifiedStakes = DelegatedRewardsDistributor.identifyModifiedStakes(
        unexpiredCreateDelegatedStakes,
        delegatedStakeAcceptanceResult.acceptedCreates
      )

      containsModifiedStake = modifiedStakes.contains((walletAddress, originalTokenLockRef))

      updatedNodeId = result.updatedCreateDelegatedStakes.get(walletAddress).flatMap(_.headOption.map(_.event.value.nodeId))
    } yield
      expect(containsModifiedStake)
        .and(expect(balance.contains(initialBalance)))
        .and(expect(updatedNodeId.contains(nodeId2)))
  }
}
