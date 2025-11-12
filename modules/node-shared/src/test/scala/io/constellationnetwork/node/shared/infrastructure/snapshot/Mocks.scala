package io.constellationnetwork.node.shared.infrastructure.snapshot

import java.security.KeyPair
import java.util.UUID

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{Async, IO}
import cats.implicits.{catsSyntaxOptionId, none, toTraverseOps}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.validated._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.delegatedStake.{
  UpdateDelegatedStakeAcceptanceManager,
  UpdateDelegatedStakeAcceptanceResult,
  UpdateDelegatedStakeValidator
}
import io.constellationnetwork.node.shared.domain.node.{UpdateNodeParametersAcceptanceManager, UpdateNodeParametersAcceptanceResult}
import io.constellationnetwork.node.shared.domain.nodeCollateral.{
  UpdateNodeCollateralAcceptanceManager,
  UpdateNodeCollateralAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.priceOracle.PricingUpdateValidator.PricingUpdateValidationErrorOr
import io.constellationnetwork.node.shared.domain.priceOracle.{PriceStateUpdater, PricingUpdateValidator}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.SpendActionValidationErrorOr
import io.constellationnetwork.node.shared.domain.swap.block._
import io.constellationnetwork.node.shared.domain.tokenlock.block._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.rewards.GlobalDelegatedRewardsDistributor
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{PricingUpdate, SpendAction, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendBlock}
import io.constellationnetwork.schema.tokenLock.TokenLockAmount.toAmount
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}

object Mocks {

  private[snapshot] def mkManager()(implicit h: Hasher[IO], sp: SecurityProvider[IO]): IO[GlobalSnapshotAcceptanceManager[IO]] = {
    // Create mock dependencies for testing
    val mockBlockAcceptanceManager = new BlockAcceptanceManager[IO] {
      override def acceptBlocksIteratively(
        blocks: List[Signed[Block]],
        context: BlockAcceptanceContext[IO],
        snapshotOrdinal: SnapshotOrdinal,
        shouldValidateCollateral: Boolean
      )(implicit hasher: Hasher[IO]): IO[BlockAcceptanceResult] =
        BlockAcceptanceResult(
          accepted = List.empty,
          notAccepted = List.empty,
          contextUpdate = BlockAcceptanceContextUpdate(
            balances = SortedMap.empty,
            lastTxRefs = SortedMap.empty,
            parentUsages = Map.empty
          )
        ).pure[IO]

      override def acceptBlock(
        block: Signed[Block],
        context: BlockAcceptanceContext[IO],
        snapshotOrdinal: SnapshotOrdinal,
        shouldValidateCollateral: Boolean
      )(implicit hasher: Hasher[IO]): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] =
        (
          BlockAcceptanceContextUpdate(
            balances = SortedMap.empty,
            lastTxRefs = SortedMap.empty,
            parentUsages = Map.empty
          ),
          initUsageCount
        ).asRight.pure[IO]
    }

    val mockAllowSpendBlockAcceptanceManager = new AllowSpendBlockAcceptanceManager[IO] {
      override def acceptBlocksIteratively(
        blocks: List[Signed[AllowSpendBlock]],
        context: AllowSpendBlockAcceptanceContext[IO],
        snapshotOrdinal: SnapshotOrdinal,
        shouldPerformMetagraphSpecificValidations: Boolean,
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[IO]): IO[AllowSpendBlockAcceptanceResult] = AllowSpendBlockAcceptanceResult(
        contextUpdate = AllowSpendBlockAcceptanceContextUpdate.empty,
        accepted = List.empty[Signed[AllowSpendBlock]],
        notAccepted = List.empty[(Signed[AllowSpendBlock], AllowSpendBlockNotAcceptedReason)]
      ).pure[IO]

      override def acceptBlock(
        block: Signed[AllowSpendBlock],
        context: AllowSpendBlockAcceptanceContext[IO],
        snapshotOrdinal: SnapshotOrdinal,
        shouldPerformMetagraphSpecificValidations: Boolean,
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[IO]): IO[Either[AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]] =
        AllowSpendBlockAcceptanceContextUpdate.empty.asRight.pure[IO]
    }

    val mockTokenLockBlockAcceptanceManager = new TokenLockBlockAcceptanceManager[IO] {
      override def acceptBlocksIteratively(
        blocks: List[Signed[TokenLockBlock]],
        context: TokenLockBlockAcceptanceContext[IO],
        snapshotOrdinal: SnapshotOrdinal,
        shouldPerformMetagraphSpecificValidations: Boolean,
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[IO]): IO[TokenLockBlockAcceptanceResult] = TokenLockBlockAcceptanceResult(
        contextUpdate = TokenLockBlockAcceptanceContextUpdate.empty,
        accepted = blocks, // Accept all blocks for testing
        notAccepted = List.empty[(Signed[TokenLockBlock], TokenLockBlockNotAcceptedReason)]
      ).pure[IO]

      override def acceptBlock(
        block: Signed[TokenLockBlock],
        context: TokenLockBlockAcceptanceContext[IO],
        snapshotOrdinal: SnapshotOrdinal,
        shouldPerformMetagraphSpecificValidations: Boolean,
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[IO]): IO[Either[TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate]] =
        TokenLockBlockAcceptanceContextUpdate.empty.asRight.pure[IO]
    }

    val mockStateChannelEventsProcessor = new GlobalSnapshotStateChannelEventsProcessor[IO] {
      override def process(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => IO[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[IO]): IO[StateChannelAcceptanceResult] = StateChannelAcceptanceResult(
        accepted = SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        calculatedCurrencyState = SortedMap.empty[Address, CurrencySnapshotWithState],
        returned = Set.empty[StateChannelOutput],
        balanceUpdate = Map.empty[Address, Balance],
        incomingCurrencySnapshotsWithState = SortedMap.empty[Address, List[CurrencySnapshotWithState]]
      ).pure[IO]

      override def processCurrencySnapshots(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => IO[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(
        implicit hasher: Hasher[IO]
      ): IO[SortedMap[Address, (NonEmptyList[(Signed[StateChannelSnapshotBinary], Option[CurrencySnapshotWithState])], BalanceUpdate)]] =
        SortedMap
          .empty[Address, (NonEmptyList[(Signed[StateChannelSnapshotBinary], Option[CurrencySnapshotWithState])], BalanceUpdate)]
          .pure[IO]
    }

    val mockUpdateNodeParametersAcceptanceManager = new UpdateNodeParametersAcceptanceManager[IO] {
      def acceptUpdateNodeParameters(
        events: List[Signed[UpdateNodeParameters]],
        lastSnapshotContext: GlobalSnapshotInfo
      ): IO[UpdateNodeParametersAcceptanceResult] =
        UpdateNodeParametersAcceptanceResult(
          accepted = List.empty,
          notAccepted = List.empty
        ).pure[IO]
    }

    val updateDelegatedStakeValidator = UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None)
    val updateDelegatedStakeAcceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](updateDelegatedStakeValidator)

    val mockUpdateNodeCollateralAcceptanceManager = new UpdateNodeCollateralAcceptanceManager[IO] {
      def accept(
        createEvents: List[Signed[UpdateNodeCollateral.Create]],
        withdrawEvents: List[Signed[UpdateNodeCollateral.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress,
        ordinal: SnapshotOrdinal,
        delegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult
      ): IO[UpdateNodeCollateralAcceptanceResult] =
        UpdateNodeCollateralAcceptanceResult(
          acceptedCreates = SortedMap.empty,
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap.empty,
          notAcceptedWithdrawals = List.empty
        ).pure[IO]
    }

    val mockSpendActionValidator = new SpendActionValidator[IO] {
      override def validate(
        spendAction: SpendAction,
        activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        allBalances: Map[Option[Address], SortedMap[Address, Balance]],
        currencyId: Address
      ): IO[SpendActionValidationErrorOr[SpendAction]] = spendAction.validNec.pure[IO]

      override def validateReturningAcceptedAndRejected(
        spendActions: Map[Address, List[SpendAction]],
        activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        allBalances: Map[Option[Address], SortedMap[Address, Balance]]
      ): IO[(Map[Address, List[SpendAction]], Map[Address, (SpendAction, List[SpendActionValidator.SpendActionValidationError])])] =
        (Map.empty[Address, List[SpendAction]], Map.empty[Address, (SpendAction, List[SpendActionValidator.SpendActionValidationError])])
          .pure[IO]
    }

    val mockPricingUpdateValidator = new PricingUpdateValidator[IO] {
      override def validate(
        pricingUpdate: PricingUpdate,
        currencyId: Address,
        lastContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress
      ): IO[PricingUpdateValidationErrorOr[PricingUpdate]] = pricingUpdate.validNec.pure[IO]

      override def validateReturningAcceptedAndRejected(
        pricingUpdates: Map[Address, List[PricingUpdate]],
        lastContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress
      ): IO[(List[PricingUpdate], List[(PricingUpdate, List[PricingUpdateValidator.PricingUpdateValidationError])])] =
        (List.empty, List.empty).pure[IO]
    }

    val mockPriceStateUpdater = new PriceStateUpdater[IO] {
      override def updatePriceState(
        lastPriceState: SortedMap[priceOracle.TokenPair, priceOracle.PriceRecord],
        acceptedPricingUpdates: List[PricingUpdate],
        epochProgress: EpochProgress
      ): IO[SortedMap[priceOracle.TokenPair, priceOracle.PriceRecord]] =
        SortedMap.empty[priceOracle.TokenPair, priceOracle.PriceRecord].pure[IO]
    }

    val globalTokenLockAcceptanceManager = GlobalTokenLockAcceptanceManager.make[IO]

    // Create the manager with mock dependencies
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)

    GlobalSnapshotAcceptanceManager
      .make[IO](
        FieldsAddedOrdinals(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty),
        MetagraphsSyncConfig(PosInt(100)),
        AppEnvironment.Dev,
        blockAcceptanceManager = mockBlockAcceptanceManager,
        allowSpendBlockAcceptanceManager = mockAllowSpendBlockAcceptanceManager,
        tokenLockBlockAcceptanceManager = mockTokenLockBlockAcceptanceManager,
        stateChannelEventsProcessor = mockStateChannelEventsProcessor,
        updateNodeParametersAcceptanceManager = mockUpdateNodeParametersAcceptanceManager,
        updateDelegatedStakeAcceptanceManager = updateDelegatedStakeAcceptanceManager,
        updateNodeCollateralAcceptanceManager = mockUpdateNodeCollateralAcceptanceManager,
        spendActionValidator = mockSpendActionValidator,
        pricingUpdateValidator = mockPricingUpdateValidator,
        priceStateUpdater = mockPriceStateUpdater,
        tokenLockAcceptanceManager = globalTokenLockAcceptanceManager,
        collateral = Amount.empty,
        withdrawalTimeLimit = EpochProgress(4L)
      )
      .pure[IO]
  }

  private[snapshot] def mkGlobalSnapshotInfo(
    activeDelegatedStakes: Option[SortedMap[Address, SortedSet[DelegatedStakeRecord]]] = None,
    delegatedStakesWithdrawals: Option[SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]] = None,
    activeTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]] = None,
    updateNodeParameters: Option[SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]] = None
  ): GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]).map(x => (x._1, Balance(1000000L))),
      SortedMap.empty,
      SortedMap.empty,
      None,
      activeTokenLocks,
      None,
      None,
      None,
      updateNodeParameters,
      activeDelegatedStakes,
      delegatedStakesWithdrawals,
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  private[snapshot] def mkTokenLock[F[_]: Async](
    keyPair: KeyPair,
    amount: TokenLockAmount,
    replaceTokenLockRef: Option[Hash],
    unlockEpoch: Option[EpochProgress] = none
  )(implicit h: Hasher[F], sp: SecurityProvider[F]): F[Signed[TokenLock]] = {
    val tokenLock = TokenLock(
      source = keyPair.getPublic.toAddress,
      amount = amount,
      fee = TokenLockFee(NonNegLong(0L)),
      parent = TokenLockReference.empty,
      currencyId = none,
      unlockEpoch = unlockEpoch,
      replaceTokenLockRef = replaceTokenLockRef
    )
    Signed.forAsyncHasher(tokenLock, keyPair)
  }

  private[snapshot] def mkDelegatedStakeCreate[F[_]: Async](
    keyPair: KeyPair,
    nodeId: PeerId,
    tokenLock: Hashed[TokenLock]
  )(implicit h: Hasher[F], sp: SecurityProvider[F]): F[Signed[UpdateDelegatedStake.Create]] = {
    val createEvent = UpdateDelegatedStake.Create(
      source = keyPair.getPublic.toAddress,
      nodeId = nodeId,
      amount = DelegatedStakeAmount.fromTokenLockAmount(tokenLock.amount),
      fee = DelegatedStakeFee(NonNegLong(0L)),
      tokenLockRef = tokenLock.hash,
      parent = DelegatedStakeReference.empty
    )
    Signed.forAsyncHasher(createEvent, keyPair)
  }

  private[snapshot] def mkDelegatedStakeWithdraw[F[_]: Async](
    keyPair: KeyPair,
    stake: Hashed[UpdateDelegatedStake.Create]
  )(implicit h: Hasher[F], sp: SecurityProvider[F]): F[Signed[UpdateDelegatedStake.Withdraw]] = {
    val withdrawEvent = UpdateDelegatedStake.Withdraw(
      source = keyPair.getPublic.toAddress,
      stakeRef = stake.hash
    )
    Signed.forAsyncHasher(withdrawEvent, keyPair)
  }

  private[snapshot] def mkUpdateNodeParameters[F[_]: Async](
    keyPair: KeyPair,
    nodeId: PeerId,
    rewardFraction: Int = 5_000_000,
    name: String = "Test Node",
    description: String = "Test Node Description"
  )(implicit h: Hasher[F], sp: SecurityProvider[F]): F[Signed[UpdateNodeParameters]] = {
    val updateNodeParameters = UpdateNodeParameters(
      source = keyPair.getPublic.toAddress,
      delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
        RewardFraction.from(rewardFraction).toOption.get
      ),
      nodeMetadataParameters = NodeMetadataParameters(
        name = name,
        description = description
      ),
      parent = UpdateNodeParametersReference.empty
    )
    Signed.forAsyncHasher(updateNodeParameters, keyPair)
  }

  private[snapshot] def mkTokenLockBlock[F[_]: Async](
    tokenLocks: List[Signed[TokenLock]]
  )(implicit h: Hasher[F], sp: SecurityProvider[F]): F[Signed[TokenLockBlock]] = {
    val tokenLockBlock = TokenLockBlock(
      roundId = RoundId(UUID.randomUUID()),
      tokenLocks = NonEmptySet.fromSet(tokenLocks.toSortedSet).get
    )
    // We need a key pair to sign the block
    KeyPairGenerator.makeKeyPair[F].flatMap(kp => Signed.forAsyncHasher(tokenLockBlock, kp))
  }

  private[snapshot] def mkGlobalDelegatedRewardsDistributor[F[_]: Async: Hasher] = GlobalDelegatedRewardsDistributor.make[F](
    AppEnvironment.Dev,
    DelegatedRewardsConfig(
      // Fixed inflation rate (3%)
      flatInflationRate = NonNegFraction.unsafeFrom(3, 100),
      emissionConfig = Map(
        AppEnvironment.Dev -> { epochProgress: EpochProgress =>
          EmissionConfigEntry(
            epochsPerYear = PosLong(733897L),
            asOfEpoch = EpochProgress(752477L), // Transition epoch
            iTarget = NonNegFraction.unsafeFrom(5, 1000), // 0.5% target
            iInitial = NonNegFraction.unsafeFrom(6, 100), // 6% initial
            lambda = NonNegFraction.unsafeFrom(1, 10), // 0.1 lambda
            iImpact = NonNegFraction.unsafeFrom(35, 100), // 0.35 impact
            totalSupply = Amount(3693588685_00000000L), // Total supply
            dagPrices = SortedMap(
              EpochProgress(0L) -> NonNegFraction.unsafeFrom(25, 1) // 25 DAG per USD
            ),
            epochsPerMonth = NonNegLong(733897L / 12)
          )
        }
      ),
      percentDistribution = Map(
        AppEnvironment.Dev -> { _ =>
          ProgramsDistributionConfig(
            Map(
//            stardustAddress -> NonNegFraction.unsafeFrom(5, 100), // 5% to stardust
//            protocolAddress -> NonNegFraction.unsafeFrom(30, 100) // 30% to protocol
            ),
            NonNegFraction.unsafeFrom(20, 100), // 20% to static validators
            NonNegFraction.unsafeFrom(45, 100) // 45% to delegators
          )
        }
      ),
      oneTimeRewards = Map(AppEnvironment.Dev -> List.empty),
      priceOracleEpoch = Map(AppEnvironment.Dev -> EpochProgress.MaxValue)
    )
  )

  private[snapshot] def delegatedRewardsFunction[F[_]: Async: Hasher](
    lastSnapshotContext: GlobalSnapshotInfo
  ): RewardsInput => F[DelegatedRewardsResult] = {
    case DelegateRewardsInput(diffs, partitionedStakes, epochProgress) =>
      mkGlobalDelegatedRewardsDistributor.distribute(
        lastSnapshotContext,
        EventTrigger,
        epochProgress,
        facilitators = List(),
        diffs,
        partitionedStakes
      )
    case _ =>
      DelegatedRewardsResult(
        SortedMap.empty,
        SortedMap.empty,
        SortedMap.empty,
        SortedSet.empty,
        SortedSet.empty,
        SortedSet.empty,
        Amount.empty
      ).pure[F]
  }

}
