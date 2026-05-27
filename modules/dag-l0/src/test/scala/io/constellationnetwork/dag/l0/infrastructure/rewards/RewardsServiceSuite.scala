package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.node.shared.config.types.EmissionConfigEntry
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.delegatedStake.{RewardsInfoCalculator, RewardsInfoStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  DelegatedRewardsDistributor,
  DelegatedRewardsResult,
  PartitionedStakeUpdates
}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.{NextDagPrice, RewardsInfo}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction}
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object RewardsServiceSuite extends SimpleIOSuite {

  // Test data helpers
  val testPeerId1: PeerId = PeerId(Hex("test-peer-1"))
  val testAddress1: Address = Address("DAG6bg5PHo9etT9wbnbxyDbnRdv7ge12RujwfeKR")
  val testEpochProgress: EpochProgress = EpochProgress(100L)
  val testAmount100: Amount = Amount(PosLong(100L))

  val testRewardsInfo = RewardsInfo(
    epochsPerYear = PosLong(100L),
    currentDagPrice = Amount(PosLong(2500000000L)),
    nextDagPrice = NextDagPrice(
      price = Amount(PosLong(3000000000L)),
      asOfEpoch = EpochProgress(200L)
    ),
    totalDelegatedAmount = Amount(PosLong(450L)),
    latestAverageRewardPerDag = BigDecimal(0.5),
    totalDagAmount = Amount(PosLong(775L)),
    totalRewardPerEpoch = Amount(PosLong(300L)),
    totalRewardsPerYearEstimate = BigDecimal(50.0)
  )

  def createMockRewards: Rewards[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] =
    new Rewards[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] {
      override def distribute(
        lastArtifact: Signed[GlobalIncrementalSnapshot],
        lastBalances: SortedMap[Address, Balance],
        acceptedTransactions: SortedSet[Signed[Transaction]],
        trigger: ConsensusTrigger,
        events: Set[GlobalSnapshotEvent],
        maybeCalculatedState: Option[DataCalculatedState] = None
      ): IO[SortedSet[RewardTransaction]] = IO.pure(SortedSet.empty)
    }

  def createMockDelegatedRewardsDistributor: DelegatedRewardsDistributor[IO] =
    new DelegatedRewardsDistributor[IO] {
      override def getEmissionConfig(epochProgress: EpochProgress): IO[EmissionConfigEntry] =
        EmissionConfigEntry(
          epochsPerYear = PosLong(100L),
          asOfEpoch = EpochProgress(0L),
          iTarget = NonNegFraction.unsafeFrom(5, 1000),
          iInitial = NonNegFraction.unsafeFrom(6, 100),
          lambda = NonNegFraction.unsafeFrom(1, 10),
          iImpact = NonNegFraction.unsafeFrom(35, 100),
          totalSupply = Amount(1000000L),
          dagPrices = SortedMap(EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1)),
          epochsPerMonth = NonNegLong(8L)
        ).pure[IO]

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

  def createMockRewardsInfoCalculator(shouldReturnRewardsInfo: Boolean): RewardsInfoCalculator[IO] =
    new RewardsInfoCalculator[IO] {
      override def calculateRewardsInfo(
        lastSnapshot: GlobalIncrementalSnapshot,
        lastSnapshotInfo: GlobalSnapshotInfo
      ): IO[Option[RewardsInfo]] =
        (if (shouldReturnRewardsInfo) Some(testRewardsInfo)
         else None).pure[IO]
    }

  def createTestSnapshot: GlobalIncrementalSnapshot = GlobalIncrementalSnapshot(
    ordinal = SnapshotOrdinal(1L),
    height = Height(1L),
    subHeight = SubHeight(1L),
    lastSnapshotHash = Hash("test-hash"),
    blocks = SortedSet.empty,
    stateChannelSnapshots = SortedMap.empty,
    rewards = SortedSet.empty,
    delegateRewards = Some(
      SortedMap(
        testPeerId1 -> SortedMap(testAddress1 -> testAmount100)
      )
    ),
    epochProgress = testEpochProgress,
    nextFacilitators = NonEmptyList.of(testPeerId1),
    tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
    stateProof = GlobalSnapshotStateProof(
      lastStateChannelSnapshotHashesProof = Hash("proof1"),
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

  def createTestSnapshotInfo: GlobalSnapshotInfo = GlobalSnapshotInfo(
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

  test("calculateAndStoreRewardsInfo stores rewards info when calculator returns Some") {
    for {
      storage <- RewardsInfoStorage.make[IO]

      calculator = createMockRewardsInfoCalculator(shouldReturnRewardsInfo = true)
      rewardsService = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculator,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      _ <- rewardsService.calculateAndStoreRewardsInfo(snapshot, snapshotInfo)
      storedRewardsInfo <- storage.getRewardsInfo
    } yield expect.same(storedRewardsInfo, Some(testRewardsInfo))
  }

  test("calculateAndStoreRewardsInfo does not store anything when calculator returns None") {
    for {
      storage <- RewardsInfoStorage.make[IO]

      calculator = createMockRewardsInfoCalculator(shouldReturnRewardsInfo = false)
      rewardsService = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculator,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      _ <- rewardsService.calculateAndStoreRewardsInfo(snapshot, snapshotInfo)
      storedRewardsInfo <- storage.getRewardsInfo
    } yield expect.same(storedRewardsInfo, None)
  }

  test("calculateAndStoreRewardsInfo calls calculator with correct parameters") {
    for {
      storage <- RewardsInfoStorage.make[IO]

      // Create a calculator that tracks the parameters it was called with
      calledWithSnapshotRef <- Ref[IO].of(Option.empty[GlobalIncrementalSnapshot])
      calledWithSnapshotInfoRef <- Ref[IO].of(Option.empty[GlobalSnapshotInfo])

      calculator = new RewardsInfoCalculator[IO] {
        override def calculateRewardsInfo(
          lastSnapshot: GlobalIncrementalSnapshot,
          lastSnapshotInfo: GlobalSnapshotInfo
        ): IO[Option[RewardsInfo]] =
          calledWithSnapshotRef.set(Some(lastSnapshot)) >>
            calledWithSnapshotInfoRef.set(Some(lastSnapshotInfo)) >>
            Some(testRewardsInfo).pure[IO]
      }

      rewardsService = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculator,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      _ <- rewardsService.calculateAndStoreRewardsInfo(snapshot, snapshotInfo)
      calledWithSnapshot <- calledWithSnapshotRef.get
      calledWithSnapshotInfo <- calledWithSnapshotInfoRef.get
    } yield
      expect.same(calledWithSnapshot, Some(snapshot)) &&
        expect.same(calledWithSnapshotInfo, Some(snapshotInfo))
  }

  test("calculateAndStoreRewardsInfo calls storage only when calculator returns Some") {
    for {
      storageRef <- Ref[IO].of(Option.empty[RewardsInfo])
      storeCallCountRef <- Ref[IO].of(0)

      storage = new RewardsInfoStorage[IO] {
        override def getRewardsInfo: IO[Option[RewardsInfo]] = storageRef.get
        override def storeRewardsInfo(rewardsInfo: RewardsInfo): IO[Unit] =
          storeCallCountRef.update(_ + 1) >>
            storageRef.set(Some(rewardsInfo))
      }

      // Test with calculator that returns Some
      calculatorWithSome = createMockRewardsInfoCalculator(shouldReturnRewardsInfo = true)
      rewardsServiceWithSome = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculatorWithSome,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      _ <- rewardsServiceWithSome.calculateAndStoreRewardsInfo(snapshot, snapshotInfo)
      callCountAfterSome <- storeCallCountRef.get

      // Reset and test with calculator that returns None
      _ <- storageRef.set(None)
      _ <- storeCallCountRef.set(0)

      calculatorWithNone = createMockRewardsInfoCalculator(shouldReturnRewardsInfo = false)
      rewardsServiceWithNone = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculatorWithNone,
        rewardsInfoStorage = storage
      )

      _ <- rewardsServiceWithNone.calculateAndStoreRewardsInfo(snapshot, snapshotInfo)
      callCountAfterNone <- storeCallCountRef.get
    } yield
      expect.same(callCountAfterSome, 1) &&
        expect.same(callCountAfterNone, 0)
  }

  test("calculateAndStoreRewardsInfo handles storage errors gracefully") {
    for {
      storageRef <- Ref[IO].of(Option.empty[RewardsInfo])
      storage = new RewardsInfoStorage[IO] {
        override def getRewardsInfo: IO[Option[RewardsInfo]] = storageRef.get
        override def storeRewardsInfo(rewardsInfo: RewardsInfo): IO[Unit] =
          IO.raiseError(new RuntimeException("Storage error"))
      }

      calculator = createMockRewardsInfoCalculator(shouldReturnRewardsInfo = true)
      rewardsService = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculator,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      result <- rewardsService.calculateAndStoreRewardsInfo(snapshot, snapshotInfo).attempt
    } yield
      expect.same(result.isLeft, true) &&
        expect.same(result.swap.getOrElse(throw new RuntimeException("Expected Left")).getMessage, "Storage error")
  }

  test("calculateAndStoreRewardsInfo handles calculator errors gracefully") {
    for {
      storage <- RewardsInfoStorage.make[IO]

      calculator = new RewardsInfoCalculator[IO] {
        override def calculateRewardsInfo(
          lastSnapshot: GlobalIncrementalSnapshot,
          lastSnapshotInfo: GlobalSnapshotInfo
        ): IO[Option[RewardsInfo]] =
          IO.raiseError(new RuntimeException("Calculator error"))
      }

      rewardsService = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculator,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      result <- rewardsService.calculateAndStoreRewardsInfo(snapshot, snapshotInfo).attempt
    } yield
      expect.same(result.isLeft, true) &&
        expect.same(result.swap.getOrElse(throw new RuntimeException("Expected Left")).getMessage, "Calculator error")
  }

  test("calculateAndStoreRewardsInfo returns testRewardsInfo on success") {
    for {
      storage <- RewardsInfoStorage.make[IO]

      calculator = createMockRewardsInfoCalculator(shouldReturnRewardsInfo = true)
      rewardsService = RewardsService(
        classicRewards = createMockRewards,
        delegatedRewards = createMockDelegatedRewardsDistributor,
        rewardsInfoCalculator = calculator,
        rewardsInfoStorage = storage
      )

      snapshot = createTestSnapshot
      snapshotInfo = createTestSnapshotInfo

      result <- rewardsService.calculateAndStoreRewardsInfo(snapshot, snapshotInfo)
    } yield expect.same(result, Some(testRewardsInfo))
  }
}
