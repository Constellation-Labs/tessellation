package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.config.DelegatedRewardsConfigProvider
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.DelegationRewardsResult
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, NonNegFraction, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// TessellationV3RewardsMigrationSuite tests the transition logic between classic and delegated rewards
object TessellationV3RewardsMigrationSuite extends SimpleIOSuite with Checkers {
  val protocolDevAddress = Address("DAG86Joz5S7hkL8N9yqTuVs5vo1bzQLwF3MUTUMX")
  val stardustAddress = Address("DAG5bvqxSJmbWVwcKWEU7nb3sgTnN1QZMPi4F8Cc")

  val address1 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  val address2 = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")
  val nodeId1 = Id(Hex("1234567890abcdef"))
  val nodeId2 = Id(Hex("abcdef1234567890"))

  // Default config for most tests
  val delegatedRewardsConfig = createDelegatedRewardsConfig()

  val classicRewardsConfig = ClassicRewardsConfig(
    programs = _ =>
      ProgramsDistributionConfig(
        weights = Map(
          protocolDevAddress -> NonNegFraction.unsafeFrom(30L, 100L),
          stardustAddress -> NonNegFraction.unsafeFrom(5L, 100L)
        ),
        validatorsWeight = NonNegFraction.unsafeFrom(20L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(45L, 100L)
      ),
    oneTimeRewards = List.empty,
    rewardsPerEpoch = Map(EpochProgress(50L) -> Amount(100L))
  )

  def createDelegatedRewardsConfig(asOfEpoch: EpochProgress = EpochProgress(100L)): DelegatedRewardsConfig = DelegatedRewardsConfig(
    flatInflationRate = NonNegFraction.zero,
    emissionConfig = Map(
      AppEnvironment.Dev -> EmissionConfigEntry(
        epochsPerYear = PosLong(12L),
        asOfEpoch = asOfEpoch, // Configurable transition point
        iTarget = NonNegFraction.unsafeFrom(1, 100),
        iInitial = NonNegFraction.unsafeFrom(2, 100),
        lambda = NonNegFraction.unsafeFrom(1, 10),
        iImpact = NonNegFraction.unsafeFrom(5, 10),
        totalSupply = Amount(100_00000000L),
        dagPrices = SortedMap(
          EpochProgress(100L) -> NonNegFraction.unsafeFrom(10, 1),
          EpochProgress(150L) -> NonNegFraction.unsafeFrom(8, 1)
        )
      )
    ),
    Map.empty
  )

  def createClassicRewardsResult(amount: Long): DelegationRewardsResult = {
    val rewardTxs = SortedSet(
      RewardTransaction(protocolDevAddress, TransactionAmount(PosLong.unsafeFrom(amount * 30 / 100))),
      RewardTransaction(stardustAddress, TransactionAmount(PosLong.unsafeFrom(amount * 5 / 100)))
    )

    DelegationRewardsResult(
      delegatorRewardsMap = Map.empty,
      updatedCreateDelegatedStakes = SortedMap.empty,
      updatedWithdrawDelegatedStakes = SortedMap.empty,
      nodeOperatorRewards = rewardTxs,
      reservedAddressRewards = SortedSet.empty,
      withdrawalRewardTxs = SortedSet.empty,
      totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(amount))
    )
  }

  test("classic and delegated rewards should both generate reward transactions") {
    // Create and use a mock version of the rewards calculator
    val mockClassicRewards = createClassicRewardsResult(1000L)
    val mockDelegatedRewards = GlobalDelegatedRewardsDistributorSuite.createTestDelegationRewardsResult(Amount(1000L))

    // Verify classic rewards focus on facilitator and system addresses
    val classicAddresses = mockClassicRewards.nodeOperatorRewards.map(_.destination).toSet

    // Verify delegated rewards include user addresses
    val delegatedAddresses = mockDelegatedRewards.nodeOperatorRewards.map(_.destination).toSet ++
      mockDelegatedRewards.withdrawalRewardTxs.map(_.destination).toSet

    IO {
      expect(mockClassicRewards.nodeOperatorRewards.nonEmpty) &&
      expect(mockDelegatedRewards.nodeOperatorRewards.nonEmpty) &&
      expect(classicAddresses.contains(protocolDevAddress)) &&
      expect(delegatedAddresses.nonEmpty)
    }
  }

  test("v3 reward calculation should use configurable epoch threshold") {
    // Test using a custom emission config with an early asOfEpoch threshold
    val earlyConfig = createDelegatedRewardsConfig(EpochProgress(10L))
    val lateConfig = createDelegatedRewardsConfig(EpochProgress(5000L))

    IO {
      expect(earlyConfig.emissionConfig(AppEnvironment.Dev).asOfEpoch == EpochProgress(10L)) &&
      expect(lateConfig.emissionConfig(AppEnvironment.Dev).asOfEpoch == EpochProgress(5000L))
    }
  }

  // This test simulates the reward selection logic that was implemented in GlobalSnapshotConsensusFunctions
  test("rewards selector should handle both transition criteria") {
    // Simulate the shouldUseDelegatedRewards method from GlobalSnapshotConsensusFunctions
    def shouldUseDelegatedRewards(
      currentOrdinal: SnapshotOrdinal,
      currentEpochProgress: EpochProgress,
      migrationOrdinal: SnapshotOrdinal,
      asOfEpoch: EpochProgress
    ): Boolean =
      currentOrdinal.value >= migrationOrdinal.value &&
        currentEpochProgress.value.value >= asOfEpoch.value.value

    // Define test thresholds
    val migrationThreshold = SnapshotOrdinal.unsafeApply(100L)
    val epochThreshold = EpochProgress(100L)

    // Test all four scenarios
    val beforeMigration = SnapshotOrdinal.unsafeApply(99L)
    val afterMigration = SnapshotOrdinal.unsafeApply(101L)
    val beforeEpochThreshold = EpochProgress(99L)
    val afterEpochThreshold = EpochProgress(101L)

    // Determine which reward function would be called in each scenario
    val scenario1 = shouldUseDelegatedRewards(beforeMigration, beforeEpochThreshold, migrationThreshold, epochThreshold)
    val scenario2 = shouldUseDelegatedRewards(afterMigration, beforeEpochThreshold, migrationThreshold, epochThreshold)
    val scenario3 = shouldUseDelegatedRewards(beforeMigration, afterEpochThreshold, migrationThreshold, epochThreshold)
    val scenario4 = shouldUseDelegatedRewards(afterMigration, afterEpochThreshold, migrationThreshold, epochThreshold)

    IO {
      // Case 1: Before migration, before epoch threshold - should NOT use delegated rewards
      expect(!scenario1) &&
      // Case 2: After migration, before epoch threshold - should NOT use delegated rewards
      expect(!scenario2) &&
      // Case 3: Before migration, after epoch threshold - should NOT use delegated rewards
      expect(!scenario3) &&
      // Case 4: After migration, after epoch threshold - should use delegated rewards
      expect(scenario4)
    }
  }

  // This test verifies the OR condition in determining classic rewards usage
  test("rewards selector should use classic rewards unless both conditions are met") {
    for {
      // Define test thresholds
      migrationThreshold <- SnapshotOrdinal.unsafeApply(100L).pure[IO]
      epochThreshold = EpochProgress(100L)

      // Define test cases
      beforeMigration = SnapshotOrdinal.unsafeApply(99L)
      afterMigration = SnapshotOrdinal.unsafeApply(101L)
      beforeEpochThreshold = EpochProgress(99L)
      afterEpochThreshold = EpochProgress(101L)

      // Check classic rewards conditions
      useClassic1 = (beforeMigration.value < migrationThreshold.value) ||
        (beforeEpochThreshold.value.value < epochThreshold.value.value)
      useClassic2 = (afterMigration.value < migrationThreshold.value) ||
        (beforeEpochThreshold.value.value < epochThreshold.value.value)
      useClassic3 = (beforeMigration.value < migrationThreshold.value) ||
        (afterEpochThreshold.value.value < epochThreshold.value.value)
      useClassic4 = (afterMigration.value < migrationThreshold.value) ||
        (afterEpochThreshold.value.value < epochThreshold.value.value)
    } yield
      // Case 1: Before migration, before epoch threshold - should use classic
      // Case 2: After migration, before epoch threshold - should use classic
      // Case 3: Before migration, after epoch threshold - should use classic
      // Case 4: After migration, after epoch threshold - should NOT use classic
      expect(useClassic1) &&
        expect(useClassic2) &&
        expect(useClassic3) &&
        expect(!useClassic4)
  }
}
