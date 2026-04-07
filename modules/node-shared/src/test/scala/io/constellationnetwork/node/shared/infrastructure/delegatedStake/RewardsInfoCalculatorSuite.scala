package io.constellationnetwork.node.shared.infrastructure.delegatedStake

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.config.types.EmissionConfigEntry
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  DelegatedRewardsDistributor,
  DelegatedRewardsResult,
  PartitionedStakeUpdates
}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object RewardsInfoCalculatorSuite extends SimpleIOSuite {

  // Test data helpers
  val testPeerId1: PeerId = PeerId(Hex("test-peer-1"))
  val testPeerId2: PeerId = PeerId(Hex("test-peer-2"))
  val testAddress1: Address = Address("DAG6bg5PHo9etT9wbnbxyDbnRdv7ge12RujwfeKR")
  val testAddress2: Address = Address("DAG4ARHfARnth2EW94GNVhWwwtZV6hqMkYYAEf46")
  val testEpochProgress: EpochProgress = EpochProgress(100L)

  val testAmount100: Amount = Amount(PosLong(100L))
  val testAmount200: Amount = Amount(PosLong(200L))

  val testEmissionConfig = EmissionConfigEntry(
    epochsPerYear = PosLong(100L),
    asOfEpoch = EpochProgress(0L),
    iTarget = NonNegFraction.unsafeFrom(5, 1000),
    iInitial = NonNegFraction.unsafeFrom(6, 100),
    lambda = NonNegFraction.unsafeFrom(1, 10),
    iImpact = NonNegFraction.unsafeFrom(35, 100),
    totalSupply = Amount(1000000L),
    dagPrices = SortedMap(EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1)),
    epochsPerMonth = NonNegLong(8L)
  )

  def createMockDelegatedRewardsDistributor: DelegatedRewardsDistributor[IO] =
    new DelegatedRewardsDistributor[IO] {
      override def getEmissionConfig(epochProgress: EpochProgress): IO[EmissionConfigEntry] =
        testEmissionConfig.pure[IO]

      override def calculateVariableInflation(epochProgress: EpochProgress, lastSnapshotContext: GlobalSnapshotInfo): IO[Amount] =
        Amount.empty.pure[IO]

      override def distribute(
        lastSnapshotContext: GlobalSnapshotInfo,
        trigger: ConsensusTrigger,
        epochProgress: EpochProgress,
        facilitators: List[(Address, PeerId)],
        delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
        partitionedRecords: PartitionedStakeUpdates
      ): IO[DelegatedRewardsResult] =
        DelegatedRewardsResult(
          SortedMap.empty,
          SortedMap.empty,
          SortedMap.empty,
          SortedSet.empty,
          SortedSet.empty,
          SortedSet.empty,
          Amount.empty
        ).pure[IO]
    }

  test("calculateRewardsInfo returns None when delegate rewards are empty") {
    val calculator = RewardsInfoCalculator.make[IO](createMockDelegatedRewardsDistributor)

    // Create a simple snapshot with empty delegate rewards
    val snapshot = GlobalIncrementalSnapshot(
      ordinal = SnapshotOrdinal(1L),
      height = Height(1L),
      subHeight = SubHeight(1L),
      lastSnapshotHash = Hash("test-hash"),
      blocks = SortedSet.empty,
      stateChannelSnapshots = SortedMap.empty,
      rewards = SortedSet.empty,
      delegateRewards = Some(SortedMap.empty),
      epochProgress = testEpochProgress,
      nextFacilitators = NonEmptyList.of(testPeerId1),
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = GlobalSnapshotStateProof(
        lastStateChannelSnapshotHashesProof = Hash.empty,
        lastTxRefsProof = Hash.empty,
        balancesProof = Hash.empty,
        lastCurrencySnapshotsProof = None,
        activeAllowSpends = None,
        activeTokenLocks = None,
        tokenLockBalances = None,
        lastAllowSpendRefs = None,
        lastTokenLockRefs = None,
        updateNodeParameters = None,
        activeDelegatedStakes = None,
        delegatedStakesWithdrawals = None,
        activeNodeCollaterals = None,
        nodeCollateralWithdrawals = None,
        priceState = None,
        lastGlobalSnapshotsWithCurrency = None,
        mptRoot = None
      ),
      allowSpendBlocks = Some(SortedSet.empty),
      tokenLockBlocks = Some(SortedSet.empty),
      spendActions = Some(SortedMap.empty),
      updateNodeParameters = Some(SortedMap.empty),
      artifacts = Some(SortedSet.empty),
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(SortedMap.empty),
      activeNodeCollaterals = Some(SortedMap.empty),
      nodeCollateralWithdrawals = Some(SortedMap.empty),
      version = SnapshotVersion("0.0.1")
    )

    val snapshotInfo = GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes = SortedMap.empty,
      lastTxRefs = SortedMap.empty,
      balances = SortedMap.empty,
      lastCurrencySnapshots = SortedMap.empty,
      lastCurrencySnapshotsProofs = SortedMap.empty,
      activeAllowSpends = None,
      activeTokenLocks = None,
      tokenLockBalances = None,
      lastAllowSpendRefs = None,
      lastTokenLockRefs = None,
      updateNodeParameters = None,
      activeDelegatedStakes = None,
      delegatedStakesWithdrawals = None,
      activeNodeCollaterals = None,
      nodeCollateralWithdrawals = None,
      priceState = None,
      metagraphSyncData = None
    )

    for {
      result <- calculator.calculateRewardsInfo(snapshot, snapshotInfo)
    } yield expect.same(result, None)
  }

  test("calculateRewardsInfo returns None when delegate rewards is None") {
    val calculator = RewardsInfoCalculator.make[IO](createMockDelegatedRewardsDistributor)

    val snapshot = GlobalIncrementalSnapshot(
      ordinal = SnapshotOrdinal(1L),
      height = Height(1L),
      subHeight = SubHeight(1L),
      lastSnapshotHash = Hash("test-hash"),
      blocks = SortedSet.empty,
      stateChannelSnapshots = SortedMap.empty,
      rewards = SortedSet.empty,
      delegateRewards = None,
      epochProgress = testEpochProgress,
      nextFacilitators = NonEmptyList.of(testPeerId1),
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = GlobalSnapshotStateProof(
        lastStateChannelSnapshotHashesProof = Hash.empty,
        lastTxRefsProof = Hash.empty,
        balancesProof = Hash.empty,
        lastCurrencySnapshotsProof = None,
        activeAllowSpends = None,
        activeTokenLocks = None,
        tokenLockBalances = None,
        lastAllowSpendRefs = None,
        lastTokenLockRefs = None,
        updateNodeParameters = None,
        activeDelegatedStakes = None,
        delegatedStakesWithdrawals = None,
        activeNodeCollaterals = None,
        nodeCollateralWithdrawals = None,
        priceState = None,
        lastGlobalSnapshotsWithCurrency = None,
        mptRoot = None
      ),
      allowSpendBlocks = Some(SortedSet.empty),
      tokenLockBlocks = Some(SortedSet.empty),
      spendActions = Some(SortedMap.empty),
      updateNodeParameters = Some(SortedMap.empty),
      artifacts = Some(SortedSet.empty),
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(SortedMap.empty),
      activeNodeCollaterals = Some(SortedMap.empty),
      nodeCollateralWithdrawals = Some(SortedMap.empty),
      version = SnapshotVersion("0.0.1")
    )

    val snapshotInfo = GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes = SortedMap.empty,
      lastTxRefs = SortedMap.empty,
      balances = SortedMap.empty,
      lastCurrencySnapshots = SortedMap.empty,
      lastCurrencySnapshotsProofs = SortedMap.empty,
      activeAllowSpends = None,
      activeTokenLocks = None,
      tokenLockBalances = None,
      lastAllowSpendRefs = None,
      lastTokenLockRefs = None,
      updateNodeParameters = None,
      activeDelegatedStakes = None,
      delegatedStakesWithdrawals = None,
      activeNodeCollaterals = None,
      nodeCollateralWithdrawals = None,
      priceState = None,
      metagraphSyncData = None
    )

    for {
      result <- calculator.calculateRewardsInfo(snapshot, snapshotInfo)
    } yield expect.same(result, None)
  }

  test("calculateRewardsInfo calculates correct rewards info with valid data") {
    val calculator = RewardsInfoCalculator.make[IO](createMockDelegatedRewardsDistributor)

    val snapshot = GlobalIncrementalSnapshot(
      ordinal = SnapshotOrdinal(1L),
      height = Height(1L),
      subHeight = SubHeight(1L),
      lastSnapshotHash = Hash("test-hash"),
      blocks = SortedSet.empty,
      stateChannelSnapshots = SortedMap.empty,
      rewards = SortedSet.empty,
      delegateRewards = Some(
        SortedMap(
          testPeerId1 -> SortedMap(testAddress1 -> testAmount100),
          testPeerId2 -> SortedMap(testAddress2 -> testAmount200)
        )
      ),
      epochProgress = testEpochProgress,
      nextFacilitators = NonEmptyList.of(testPeerId1),
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = GlobalSnapshotStateProof(
        lastStateChannelSnapshotHashesProof = Hash.empty,
        lastTxRefsProof = Hash.empty,
        balancesProof = Hash.empty,
        lastCurrencySnapshotsProof = None,
        activeAllowSpends = None,
        activeTokenLocks = None,
        tokenLockBalances = None,
        lastAllowSpendRefs = None,
        lastTokenLockRefs = None,
        updateNodeParameters = None,
        activeDelegatedStakes = None,
        delegatedStakesWithdrawals = None,
        activeNodeCollaterals = None,
        nodeCollateralWithdrawals = None,
        priceState = None,
        lastGlobalSnapshotsWithCurrency = None,
        mptRoot = None
      ),
      allowSpendBlocks = Some(SortedSet.empty),
      tokenLockBlocks = Some(SortedSet.empty),
      spendActions = Some(SortedMap.empty),
      updateNodeParameters = Some(SortedMap.empty),
      artifacts = Some(SortedSet.empty),
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(SortedMap.empty),
      activeNodeCollaterals = Some(SortedMap.empty),
      nodeCollateralWithdrawals = Some(SortedMap.empty),
      version = SnapshotVersion("0.0.1")
    )

    val snapshotInfo = GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes = SortedMap.empty,
      lastTxRefs = SortedMap.empty,
      balances = SortedMap.empty,
      lastCurrencySnapshots = SortedMap.empty,
      lastCurrencySnapshotsProofs = SortedMap.empty,
      activeAllowSpends = None,
      activeTokenLocks = None,
      tokenLockBalances = None,
      lastAllowSpendRefs = None,
      lastTokenLockRefs = None,
      updateNodeParameters = None,
      activeDelegatedStakes = None,
      delegatedStakesWithdrawals = None,
      activeNodeCollaterals = None,
      nodeCollateralWithdrawals = None,
      priceState = None,
      metagraphSyncData = None
    )

    for {
      result <- calculator.calculateRewardsInfo(snapshot, snapshotInfo)
    } yield
      result match {
        case Some(rewardsInfo) =>
          expect.same(rewardsInfo.epochsPerYear, testEmissionConfig.epochsPerYear) &&
          expect.same(rewardsInfo.totalRewardPerEpoch, Amount(PosLong(300L))) // 100 + 200 = 300
        case None => failure("Expected Some(RewardsInfo) but got None")
      }
  }
}
