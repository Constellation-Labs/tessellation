package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import java.lang.management.ManagementFactory

import cats.data.NonEmptyList
import cats.data.NonEmptySetImpl.catsDataInstancesForNonEmptySet
import cats.effect.{Async, Sync}
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.node.shared.config.types.{FieldsAddedOrdinals, MetagraphsSyncConfig}
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.delegatedStake.{
  UpdateDelegatedStakeAcceptanceManager,
  UpdateDelegatedStakeAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersAcceptanceManager
import io.constellationnetwork.node.shared.domain.nodeCollateral.{
  UpdateNodeCollateralAcceptanceManager,
  UpdateNodeCollateralAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.priceOracle.PricingUpdateValidator.PricingUpdateValidationError
import io.constellationnetwork.node.shared.domain.priceOracle.{PriceStateUpdater, PricingUpdateValidator}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.SpendActionValidationError
import io.constellationnetwork.node.shared.domain.swap.block.{AllowSpendBlockAcceptanceManager, AllowSpendBlockAcceptanceResult}
import io.constellationnetwork.node.shared.domain.tokenlock.block.{TokenLockBlockAcceptanceManager, TokenLockBlockAcceptanceResult}
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.StateChangesAccumulator
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralRecord, PendingNodeCollateralWithdrawal, UpdateNodeCollateral}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.priceOracle.{PriceRecord, TokenPair}
import io.constellationnetwork.schema.snapshot.MetagraphSyncDataInfo
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.producer.StatefulMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import fs2.Stream
import io.circe.Json
import io.circe.disjunctionCodecs._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class PartitionedRecords[A, B](
  existing: SortedMap[Address, A],
  unexpired: SortedMap[Address, B],
  expired: SortedMap[Address, B]
)

case class MerkleTreeResult(
  tree: Option[io.constellationnetwork.merkletree.MerkleTree],
  proofs: SortedMap[Address, Proof]
)

case class ArtifactValidationResult(
  acceptedSpendActions: Map[Address, List[SpendAction]],
  rejectedSpendActions: Map[Address, (SpendAction, List[SpendActionValidationError])],
  acceptedPricingUpdates: List[PricingUpdate],
  rejectedPricingUpdates: List[(PricingUpdate, List[PricingUpdateValidationError])]
)

/** Result of cleaning state maps - includes cleaned maps and removed keys for MPT sync */
case class CleanedStateMapsResult(
  cleanedAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
  cleanedTokenLockBalances: SortedMap[Address, SortedMap[Address, Balance]],
  cleanedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
  cleanedCreateDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
  cleanedWithdrawDelegatedStakes: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
  cleanedCreateNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]],
  cleanedWithdrawNodeCollaterals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
  // Removed keys for MPT incremental sync
  removedDelegatedStakeKeys: Set[Address],
  removedDelegatedStakeWithdrawalKeys: Set[Address],
  removedNodeCollateralKeys: Set[Address],
  removedNodeCollateralWithdrawalKeys: Set[Address]
)

trait GlobalSnapshotAcceptanceManager[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    blocksForAcceptance: List[Signed[Block]],
    allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
    tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
    scEvents: List[StateChannelOutput],
    unpEvents: List[Signed[UpdateNodeParameters]],
    cdsEvents: List[Signed[UpdateDelegatedStake.Create]],
    wdsEvents: List[Signed[UpdateDelegatedStake.Withdraw]],
    cncEvents: List[Signed[UpdateNodeCollateral.Create]],
    wncEvents: List[Signed[UpdateNodeCollateral.Withdraw]],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: RewardsInput => F[DelegatedRewardsResult],
    validationType: StateChannelValidationType,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  ): F[
    (
      BlockAcceptanceResult,
      AllowSpendBlockAcceptanceResult,
      TokenLockBlockAcceptanceResult,
      UpdateDelegatedStakeAcceptanceResult,
      UpdateNodeCollateralAcceptanceResult,
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput],
      SortedSet[RewardTransaction],
      GlobalSnapshotInfo,
      GlobalSnapshotStateProof,
      Map[Address, List[SpendAction]],
      SortedMap[Id, Signed[UpdateNodeParameters]],
      SortedSet[SharedArtifact],
      SortedMap[PeerId, Map[Address, Amount]]
    )
  ]
}

object GlobalSnapshotAcceptanceManager {

  private case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: Parallel: HasherSelector: SecurityProvider: JsonSerializer](
    fieldsAddedOrdinals: FieldsAddedOrdinals,
    metagraphsSyncConfig: MetagraphsSyncConfig,
    environment: AppEnvironment,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    updateNodeParametersAcceptanceManager: UpdateNodeParametersAcceptanceManager[F],
    updateDelegatedStakeAcceptanceManager: UpdateDelegatedStakeAcceptanceManager[F],
    updateNodeCollateralAcceptanceManager: UpdateNodeCollateralAcceptanceManager[F],
    spendActionValidator: SpendActionValidator[F],
    pricingUpdateValidator: PricingUpdateValidator[F],
    priceStateUpdater: PriceStateUpdater[F],
    collateral: Amount,
    withdrawalTimeLimit: EpochProgress,
    mptStore: MptStore[F, GlobalStateKey],
    loggerBundle: LoggerBundle[F]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): GlobalSnapshotAcceptanceManager[F] = {
    val artifactEmissionManager = ArtifactEmissionManager.make[F]()
    val tipUsageManager = TipUsageManager.make[F]()
    val metagraphSyncManager = MetagraphSyncManager.make[F](metagraphsSyncConfig)
    val rewardAcceptanceManager = RewardAcceptanceManager.make[F]()
    val allowSpendStateManager = AllowSpendStateManager.make[F]()
    val tokenLockStateManager = TokenLockStateManager.make[F]()
    val spendTransactionBalanceManager = SpendTransactionBalanceManager.make[F]()
    val delegatedStakeStateManager = DelegatedStakeStateManager.make[F]()
    val nodeCollateralStateManager = NodeCollateralStateManager.make[F]()
    val transactionReferenceManager = TransactionReferenceManager.make[F]()

    val blockAcceptanceCoordinatorManager = BlockAcceptanceCoordinatorManager.make[F](
      blockAcceptanceManager,
      allowSpendBlockAcceptanceManager,
      tokenLockBlockAcceptanceManager,
      tipUsageManager,
      collateral
    )

    new GlobalSnapshotAcceptanceManager[F] {
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](this.getClass)

      case class InitialData(
        blockResult: BlockAcceptanceResult,
        delegatedResult: UpdateDelegatedStakeAcceptanceResult,
        nodeParamsResult: SortedMap[Id, Signed[UpdateNodeParameters]],
        existingStakes: PartitionedRecords[SortedSet[DelegatedStakeRecord], SortedSet[PendingDelegatedStakeWithdrawal]]
      )

      private def acceptInitialData(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        blocksForAcceptance: List[Signed[Block]],
        cdsEvents: List[Signed[UpdateDelegatedStake.Create]],
        wdsEvents: List[Signed[UpdateDelegatedStake.Withdraw]],
        unpEvents: List[Signed[UpdateNodeParameters]],
        lastSnapshotContext: GlobalSnapshotInfo,
        lastActiveTips: SortedSet[ActiveTip],
        lastDeprecatedTips: SortedSet[DeprecatedTip],
        acceptedGlobalTokenLocks: List[Signed[TokenLock]]
      )(
        implicit hasher: Hasher[F]
      ): F[InitialData] =
        for {
          blockResult <- blockAcceptanceCoordinatorManager.acceptBlocks(
            blocksForAcceptance,
            lastSnapshotContext,
            lastActiveTips,
            lastDeprecatedTips,
            ordinal
          )

          unexpiredStakes <- delegatedStakeStateManager
            .processExistingDelegatedStakes(
              lastSnapshotContext,
              epochProgress,
              acceptedGlobalTokenLocks,
              withdrawalTimeLimit
            )

          delegatedResult <- updateDelegatedStakeAcceptanceManager.accept(
            cdsEvents,
            wdsEvents,
            lastSnapshotContext,
            epochProgress,
            ordinal,
            acceptedGlobalTokenLocks
          )

          nodeParamsResult <- updateNodeParametersAcceptanceManager
            .acceptUpdateNodeParameters(unpEvents, lastSnapshotContext)
            .map(acceptanceResult =>
              SortedMap.from(
                acceptanceResult.accepted.flatMap(signed => signed.proofs.toList.map(proof => (proof.id, signed)))
              )
            )
        } yield
          InitialData(
            blockResult,
            delegatedResult,
            nodeParamsResult,
            unexpiredStakes
          )

      private def acceptNodeCollateral(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        cncEvents: List[Signed[UpdateNodeCollateral.Create]],
        wncEvents: List[Signed[UpdateNodeCollateral.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        delegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult
      ): F[UpdateNodeCollateralAcceptanceResult] =
        updateNodeCollateralAcceptanceManager.accept(
          cncEvents,
          wncEvents,
          lastSnapshotContext,
          epochProgress,
          ordinal,
          delegatedStakeAcceptanceResult
        )

      private def processStateChannelEvents(
        ordinal: SnapshotOrdinal,
        lastSnapshotContext: GlobalSnapshotInfo,
        updatedGlobalBalances: SortedMap[Address, Balance],
        scEvents: List[StateChannelOutput],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[StateChannelAcceptanceResult] =
        stateChannelEventsProcessor.process(
          ordinal,
          lastSnapshotContext.copy(balances = updatedGlobalBalances),
          scEvents,
          validationType,
          getGlobalSnapshotByOrdinal
        )

      private def calculateRewards(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        tessellation3MigrationStartingOrdinal: SnapshotOrdinal,
        acceptedTransactions: SortedSet[Signed[Transaction]],
        delegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult,
        unexpiredStakes: PartitionedRecords[SortedSet[DelegatedStakeRecord], SortedSet[PendingDelegatedStakeWithdrawal]],
        calculateRewardsFn: RewardsInput => F[DelegatedRewardsResult]
      ): F[DelegatedRewardsResult] = {
        val unexpiredCreateDelegatedStakes = unexpiredStakes.existing
        val unexpiredWithdrawalsDelegatedStaking = unexpiredStakes.unexpired
        val expiredWithdrawalsDelegatedStaking = unexpiredStakes.expired

        if (ordinal.value < tessellation3MigrationStartingOrdinal.value) {
          calculateRewardsFn(ClassicRewardsInput(acceptedTransactions))
        } else {
          calculateRewardsFn(
            DelegateRewardsInput(
              delegatedStakeAcceptanceResult,
              PartitionedStakeUpdates(
                unexpiredCreateDelegatedStakes,
                unexpiredWithdrawalsDelegatedStaking,
                expiredWithdrawalsDelegatedStaking
              ),
              epochProgress
            )
          )
        }
      }

      private def validateArtifacts(
        epochProgress: EpochProgress,
        spendActions: Map[Address, List[SpendAction]],
        pricingUpdates: Map[Address, List[PricingUpdate]],
        lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        currencyBalances: Map[Option[Address], SortedMap[Address, Balance]],
        globalBalances: Map[Option[Address], SortedMap[Address, Balance]],
        lastSnapshotContext: GlobalSnapshotInfo
      ): F[ArtifactValidationResult] = {
        val shouldParallelize = spendActions.size > 100 || pricingUpdates.size > 100

        if (shouldParallelize) {
          (
            spendActionValidator.validateReturningAcceptedAndRejected(
              spendActions,
              lastActiveAllowSpends,
              currencyBalances ++ globalBalances
            ),
            pricingUpdateValidator.validateReturningAcceptedAndRejected(
              pricingUpdates,
              lastSnapshotContext,
              epochProgress
            )
          ).parMapN {
            case ((acceptedSpend, rejectedSpend), (acceptedPricing, rejectedPricing)) =>
              ArtifactValidationResult(acceptedSpend, rejectedSpend, acceptedPricing, rejectedPricing)
          }
        } else {
          for {
            (acceptedSpend, rejectedSpend) <- spendActionValidator.validateReturningAcceptedAndRejected(
              spendActions,
              lastActiveAllowSpends,
              currencyBalances ++ globalBalances
            )
            (acceptedPricing, rejectedPricing) <- pricingUpdateValidator.validateReturningAcceptedAndRejected(
              pricingUpdates,
              lastSnapshotContext,
              epochProgress
            )
          } yield ArtifactValidationResult(acceptedSpend, rejectedSpend, acceptedPricing, rejectedPricing)
        }
      }

      private def acceptAllowSpendAndTokenLockBlocks(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
        tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
        lastSnapshotContext: GlobalSnapshotInfo,
        fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[(AllowSpendBlockAcceptanceResult, TokenLockBlockAcceptanceResult)] = {
        val shouldParallelize =
          allowSpendBlocksForAcceptance.nonEmpty &&
            tokenLockBlocksForAcceptance.nonEmpty &&
            (allowSpendBlocksForAcceptance.size + tokenLockBlocksForAcceptance.size) > 20

        if (shouldParallelize) {
          (
            blockAcceptanceCoordinatorManager.acceptAllowSpendBlocks(
              allowSpendBlocksForAcceptance,
              lastSnapshotContext,
              ordinal,
              fixingAllowSpendAndTokenLockValidation,
              epochProgress
            ),
            blockAcceptanceCoordinatorManager.acceptTokenLockBlocks(
              tokenLockBlocksForAcceptance,
              lastSnapshotContext,
              ordinal,
              fixingAllowSpendAndTokenLockValidation,
              epochProgress
            )
          ).parTupled
        } else {
          for {
            allowSpend <- blockAcceptanceCoordinatorManager.acceptAllowSpendBlocks(
              allowSpendBlocksForAcceptance,
              lastSnapshotContext,
              ordinal,
              fixingAllowSpendAndTokenLockValidation,
              epochProgress
            )
            tokenLock <- blockAcceptanceCoordinatorManager.acceptTokenLockBlocks(
              tokenLockBlocksForAcceptance,
              lastSnapshotContext,
              ordinal,
              fixingAllowSpendAndTokenLockValidation,
              epochProgress
            )
          } yield (allowSpend, tokenLock)
        }
      }

      private def buildMerkleTreeAndProofs(
        ordinal: SnapshotOrdinal,
        updatedLastCurrencySnapshots: SortedMap[Address, Either[
          Signed[CurrencySnapshot],
          (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)
        ]]
      )(implicit hasher: Hasher[F]): F[MerkleTreeResult] = {

        import io.constellationnetwork.merkletree.syntax._
        val batchSize = 256

        hasher.getLogic(ordinal) match {

          case JsonHash =>
            for {
              maybeTree <- updatedLastCurrencySnapshots.merkleTree[F]

              proofs <- maybeTree match {
                case Some(tree) =>
                  updatedLastCurrencySnapshots.toList
                    .grouped(batchSize)
                    .toList
                    .traverse { batch =>
                      Async[F].cede *> batch.traverse {
                        case (address, state) =>
                          (address, state).hash
                            .flatMap(tree.findPath[F])
                            .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                            .map(address -> _)
                      }
                    }
                    .flatMap(results => Async[F].cede.as(SortedMap.from(results.flatten)))

                case None =>
                  Async[F].pure(SortedMap.empty[Address, Proof])
              }
            } yield MerkleTreeResult(maybeTree, proofs)

          case KryoHash =>
            val converted: SortedMap[Address, Either[
              Signed[CurrencySnapshot],
              (Signed[CurrencyIncrementalSnapshotV1], CurrencySnapshotInfoV1)
            ]] =
              updatedLastCurrencySnapshots.map {
                case (address, Left(snapshot)) => (address, Left(snapshot))
                case (address, Right((Signed(incrementalSnapshot, proofs), info))) =>
                  address ->
                    Right(
                      (
                        Signed(CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(incrementalSnapshot), proofs),
                        CurrencySnapshotInfoV1.fromCurrencySnapshotInfo(info)
                      )
                    )
              }

            for {
              maybeTree <- converted.merkleTree[F]

              proofs <- maybeTree match {
                case Some(tree) =>
                  converted.toList
                    .grouped(batchSize)
                    .toList
                    .traverse { batch =>
                      Async[F].cede *> batch.traverse {
                        case (address, state) =>
                          (address, state).hash
                            .flatMap(tree.findPath[F])
                            .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                            .map(address -> _)
                      }
                    }
                    .flatMap(results => Async[F].cede.as(SortedMap.from(results.flatten)))

                case None =>
                  Async[F].pure(SortedMap.empty[Address, Proof])
              }
            } yield MerkleTreeResult(maybeTree, proofs)
        }
      }

      /** Cleans empty entries from state maps and computes removed keys in a single pass. For each map type, we:
        *   1. Filter out entries with empty sets (cleaning) 2. Identify keys that were non-empty in previous state but empty/missing in new
        *      state (removal tracking)
        */
      private def cleanStateMaps(
        updatedAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        updatedTokenLockBalances: SortedMap[Address, SortedMap[Address, Balance]],
        updatedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
        updatedCreateDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
        updatedWithdrawDelegatedStakes: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
        updatedCreateNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]],
        updatedWithdrawNodeCollaterals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
        // Previous state for computing removed keys
        lastActiveDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
        lastDelegatedStakeWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
        lastActiveNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]],
        lastNodeCollateralWithdrawals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]
      ): CleanedStateMapsResult = {
        val cleanedAllowSpends = updatedAllowSpends.map {
          case (outerKey, innerMap) =>
            val cleanedInnerMap = innerMap.filter { case (_, allowSpendSet) => allowSpendSet.nonEmpty }
            (outerKey, cleanedInnerMap)
        }.filter { case (_, innerMap) => innerMap.nonEmpty }

        val cleanedTokenLockBalances = updatedTokenLockBalances.filter { case (_, tokenLockBalances) => tokenLockBalances.nonEmpty }
        val cleanedGlobalTokenLocks = updatedGlobalTokenLocks.filter { case (_, tokenLocks) => tokenLocks.nonEmpty }
        val cleanedCreateDelegatedStakes = updatedCreateDelegatedStakes.filter { case (_, records) => records.nonEmpty }
        val cleanedWithdrawDelegatedStakes = updatedWithdrawDelegatedStakes.filter { case (_, records) => records.nonEmpty }
        val cleanedCreateNodeCollaterals = updatedCreateNodeCollaterals.filter { case (_, records) => records.nonEmpty }
        val cleanedWithdrawNodeCollaterals = updatedWithdrawNodeCollaterals.filter { case (_, records) => records.nonEmpty }

        // Compute removed keys: addresses that had non-empty sets in previous state but are now empty/missing
        // This is O(m) where m = keys in previous state, with O(1) Set contains checks on cleaned keysets
        val cleanedDelegatedStakeKeySet = cleanedCreateDelegatedStakes.keySet
        val cleanedWithdrawDelegatedStakeKeySet = cleanedWithdrawDelegatedStakes.keySet
        val cleanedNodeCollateralKeySet = cleanedCreateNodeCollaterals.keySet
        val cleanedWithdrawNodeCollateralKeySet = cleanedWithdrawNodeCollaterals.keySet

        val removedDelegatedStakeKeys = lastActiveDelegatedStakes.collect {
          case (address, stakes) if stakes.nonEmpty && !cleanedDelegatedStakeKeySet.contains(address) => address
        }.toSet

        val removedDelegatedStakeWithdrawalKeys = lastDelegatedStakeWithdrawals.collect {
          case (address, withdrawals) if withdrawals.nonEmpty && !cleanedWithdrawDelegatedStakeKeySet.contains(address) => address
        }.toSet

        val removedNodeCollateralKeys = lastActiveNodeCollaterals.collect {
          case (address, collaterals) if collaterals.nonEmpty && !cleanedNodeCollateralKeySet.contains(address) => address
        }.toSet

        val removedNodeCollateralWithdrawalKeys = lastNodeCollateralWithdrawals.collect {
          case (address, withdrawals) if withdrawals.nonEmpty && !cleanedWithdrawNodeCollateralKeySet.contains(address) => address
        }.toSet

        CleanedStateMapsResult(
          cleanedAllowSpends,
          cleanedTokenLockBalances,
          cleanedGlobalTokenLocks,
          cleanedCreateDelegatedStakes,
          cleanedWithdrawDelegatedStakes,
          cleanedCreateNodeCollaterals,
          cleanedWithdrawNodeCollaterals,
          removedDelegatedStakeKeys,
          removedDelegatedStakeWithdrawalKeys,
          removedNodeCollateralKeys,
          removedNodeCollateralWithdrawalKeys
        )
      }

      private def buildGlobalSnapshotInfo(
        ordinal: SnapshotOrdinal,
        tessellation3MigrationStartingOrdinal: SnapshotOrdinal,
        tessellation301MigrationStartingOrdinal: SnapshotOrdinal,
        metagraphSyncDataStartingOrdinal: SnapshotOrdinal,
        lastSnapshotContext: GlobalSnapshotInfo,
        acceptanceResult: BlockAcceptanceResult,
        updatedLastStateChannelSnapshotHashes: SortedMap[Address, Hash],
        transactionsRefs: SortedMap[Address, TransactionReference],
        updatedBalancesBySpendTransactions: SortedMap[Address, Balance],
        updatedLastCurrencySnapshots: SortedMap[Address, Either[Signed[
          CurrencySnapshot
        ], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]],
        updatedLastCurrencySnapshotProofs: SortedMap[Address, Proof],
        updatedAllowSpendsCleaned: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        updatedGlobalTokenLocksCleaned: SortedMap[Address, SortedSet[Signed[TokenLock]]],
        updatedTokenLockBalancesCleaned: SortedMap[Address, SortedMap[Address, Balance]],
        updatedAllowSpendRefs: SortedMap[Address, AllowSpendReference],
        updatedTokenLockRefs: SortedMap[Address, TokenLockReference],
        updatedUpdateNodeParameters: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
        updatedCreateDelegatedStakesCleaned: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
        updatedWithdrawDelegatedStakesCleaned: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
        updatedCreateNodeCollateralsCleaned: SortedMap[Address, SortedSet[NodeCollateralRecord]],
        updatedWithdrawNodeCollateralsCleaned: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
        updatedPriceState: SortedMap[TokenPair, PriceRecord],
        updatedAcceptedMetagraphSyncData: SortedMap[Address, MetagraphSyncDataInfo]
      ): GlobalSnapshotInfo =
        GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          if (ordinal < tessellation3MigrationStartingOrdinal)
            lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs
          else transactionsRefs,
          updatedBalancesBySpendTransactions,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedAllowSpendsCleaned.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedGlobalTokenLocksCleaned.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedTokenLockBalancesCleaned.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedAllowSpendRefs.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedTokenLockRefs.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedUpdateNodeParameters.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedCreateDelegatedStakesCleaned.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedWithdrawDelegatedStakesCleaned.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedCreateNodeCollateralsCleaned.some,
          if (ordinal < tessellation3MigrationStartingOrdinal) none else updatedWithdrawNodeCollateralsCleaned.some,
          if (ordinal < tessellation301MigrationStartingOrdinal) none else updatedPriceState.some,
          if (ordinal < metagraphSyncDataStartingOrdinal) none else updatedAcceptedMetagraphSyncData.some
        )

      def accept(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        blocksForAcceptance: List[Signed[Block]],
        allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
        tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
        scEvents: List[StateChannelOutput],
        unpEvents: List[Signed[UpdateNodeParameters]],
        cdsEvents: List[Signed[UpdateDelegatedStake.Create]],
        wdsEvents: List[Signed[UpdateDelegatedStake.Withdraw]],
        cncEvents: List[Signed[UpdateNodeCollateral.Create]],
        wncEvents: List[Signed[UpdateNodeCollateral.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        lastActiveTips: SortedSet[ActiveTip],
        lastDeprecatedTips: SortedSet[DeprecatedTip],
        calculateRewardsFn: RewardsInput => F[DelegatedRewardsResult],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      ): F[
        (
          BlockAcceptanceResult,
          AllowSpendBlockAcceptanceResult,
          TokenLockBlockAcceptanceResult,
          UpdateDelegatedStakeAcceptanceResult,
          UpdateNodeCollateralAcceptanceResult,
          SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
          Set[StateChannelOutput],
          SortedSet[RewardTransaction],
          GlobalSnapshotInfo,
          GlobalSnapshotStateProof,
          Map[Address, List[SpendAction]],
          SortedMap[Id, Signed[UpdateNodeParameters]],
          SortedSet[SharedArtifact],
          SortedMap[PeerId, Map[Address, Amount]]
        )
      ] = {
        implicit val hasher: Hasher[F] = HasherSelector[F].getForOrdinal(ordinal)

        val tessellation3MigrationStartingOrdinal = fieldsAddedOrdinals.tessellation3Migration
          .getOrElse(environment, SnapshotOrdinal.MinValue)

        val tessellation301MigrationStartingOrdinal = fieldsAddedOrdinals.tessellation301Migration
          .getOrElse(environment, SnapshotOrdinal.MinValue)

        val metagraphSyncDataStartingOrdinal = fieldsAddedOrdinals.metagraphSyncData
          .getOrElse(environment, SnapshotOrdinal.MinValue)

        val fixingAllowSpendAndTokenLockValidation = fieldsAddedOrdinals.fixingAllowSpendAndTokenLockValidation
          .getOrElse(environment, SnapshotOrdinal.MinValue)

        loggerBundle.app.withOrdinal(ordinal) {
          for {
            (allowSpendBlockAcceptanceResult, tokenLockBlockAcceptanceResult) <-
              acceptAllowSpendAndTokenLockBlocks(
                ordinal,
                epochProgress,
                allowSpendBlocksForAcceptance,
                tokenLockBlocksForAcceptance,
                lastSnapshotContext,
                fixingAllowSpendAndTokenLockValidation
              )

            acceptedGlobalAllowSpends = allowSpendBlockAcceptanceResult.accepted.flatMap(_.value.transactions.toList)
            acceptedGlobalTokenLocks <- tokenLockStateManager.acceptReplacementTokenLocks(
              tokenLockBlockAcceptanceResult.accepted.flatMap(_.value.tokenLocks.toList),
              lastSnapshotContext
            )

            initialData <-
              acceptInitialData(
                ordinal,
                epochProgress,
                blocksForAcceptance,
                cdsEvents,
                wdsEvents,
                unpEvents,
                lastSnapshotContext,
                lastActiveTips,
                lastDeprecatedTips,
                acceptedGlobalTokenLocks
              )

            nodeCollateralAcceptanceResult <- acceptNodeCollateral(
              ordinal,
              epochProgress,
              cncEvents,
              wncEvents,
              lastSnapshotContext,
              initialData.delegatedResult
            )

            updatedUpdateNodeParameters = lastSnapshotContext.updateNodeParameters.getOrElse(
              SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]
            ) ++ initialData.nodeParamsResult.view.mapValues(unp => (unp, ordinal))

            acceptedTransactions = initialData.blockResult.accepted.flatMap {
              case (block, _) => block.value.transactions.toSortedSet
            }.toSortedSet

            updatedGlobalBalances = lastSnapshotContext.balances ++ initialData.blockResult.contextUpdate.balances

            StateChannelAcceptanceResult(
              scSnapshots,
              currencySnapshots,
              returnedSCEvents,
              currencyAcceptanceBalanceUpdate,
              incomingCurrencySnapshots
            ) <- processStateChannelEvents(
              ordinal,
              lastSnapshotContext,
              updatedGlobalBalances,
              scEvents,
              validationType,
              getGlobalSnapshotByOrdinal
            )

            (transactionsRefs, transactionsRefsDeltas) = transactionReferenceManager.acceptTransactionRefs(
              lastSnapshotContext.lastTxRefs,
              initialData.blockResult.contextUpdate.lastTxRefs,
              acceptedTransactions
            )

            currencyBalances = currencySnapshots.toList.map {
              case (_, Left(_))              => Map.empty[Option[Address], SortedMap[Address, Balance]]
              case (address, Right((_, si))) => Map(address.some -> si.balances)
            }.foldLeft(Map.empty[Option[Address], SortedMap[Address, Balance]])(_ ++ _)

            sharedArtifacts = incomingCurrencySnapshots.toList.map {
              case (address, snapshots) =>
                val artifacts: List[SharedArtifact] = snapshots.flatMap {
                  case Left(_)       => Nil
                  case Right((s, _)) => s.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).toList
                }
                Map(address -> artifacts)
            }.foldLeft(Map.empty[Address, List[SharedArtifact]])(_ |+| _).view

            sCSnapshotHashes <- scSnapshots.toList.traverse {
              case (address, nel) => nel.last.toHashed.map(address -> _.hash)
            }.map(_.toMap)

            DelegatedRewardsResult(
              delegatorRewardsMap,
              updatedCreateDelegatedStakes,
              updatedWithdrawDelegatedStakes,
              nodeOperatorRewards,
              reservedAddressRewards,
              withdrawalRewardTxs,
              _
            ) <- calculateRewards(
              ordinal,
              epochProgress,
              tessellation3MigrationStartingOrdinal,
              acceptedTransactions,
              initialData.delegatedResult,
              initialData.existingStakes,
              calculateRewardsFn
            )

            (updatedBalancesByRewards, acceptedRewardTxs, rewardBalancesDelta) = rewardAcceptanceManager.acceptRewardTxs(
              updatedGlobalBalances ++ currencyAcceptanceBalanceUpdate,
              withdrawalRewardTxs ++ nodeOperatorRewards ++ reservedAddressRewards
            )

            globalBalances = Map(none[Address] -> updatedBalancesByRewards)

            spendActions = sharedArtifacts
              .mapValues(_.collect { case sa: SpendAction => sa })
              .filter { case (_, actions) => actions.nonEmpty }
              .toMap

            pricingUpdates = sharedArtifacts
              .mapValues(_.collect { case pu: PricingUpdate => pu })
              .filter { case (_, updates) => updates.nonEmpty }
              .toMap

            globalSnapshotsProcessed = sharedArtifacts.view
              .mapValues(_.collect { case pu: GlobalSnapshotsProcessed => pu })
              .filter { case (_, updates) => updates.nonEmpty }
              .toMap

            lastActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
              SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
            )

            ArtifactValidationResult(
              acceptedSpendActions,
              rejectedSpendActions,
              acceptedPricingUpdates,
              rejectedPricingUpdates
            ) <- validateArtifacts(
              epochProgress,
              spendActions,
              pricingUpdates,
              lastActiveAllowSpends,
              currencyBalances,
              globalBalances,
              lastSnapshotContext
            )
            acceptedSpendActionsMessage = s"[ORDINAL=$ordinal] Accepted spend actions: ${acceptedSpendActions.show}"
            rejectedSpendActionMessage = s"[ORDINAL=$ordinal] Rejected spend actions: ${rejectedSpendActions.show}"
            acceptedPricingUpdatesMessage = s"[ORDINAL=$ordinal] Accepted pricing updates: ${acceptedPricingUpdates.show}"
            rejectedPricingUpdatesMessage = s"[ORDINAL=$ordinal] Rejected pricing updates: ${rejectedPricingUpdates.show}"

            _ <- logger.debug(acceptedSpendActionsMessage)
            _ <- logger.debug(rejectedSpendActionMessage)
            _ <- logger.debug(acceptedPricingUpdatesMessage)
            _ <- logger.debug(rejectedPricingUpdatesMessage)

            _ <- loggerBundle.app.info(acceptedSpendActionsMessage)
            _ <- loggerBundle.app.info(rejectedSpendActionMessage)
            _ <- loggerBundle.app.info(acceptedPricingUpdatesMessage)
            _ <- loggerBundle.app.info(rejectedPricingUpdatesMessage)

            updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
            updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

            activeAllowSpendsFromCurrencySnapshots = currencySnapshots
              .mapFilter(_.toOption.flatMap { case (_, info) => info.activeAllowSpends })

            globalAllowSpends = acceptedGlobalAllowSpends
              .groupBy(_.value.source)
              .view
              .mapValues(SortedSet.from(_))
              .to(SortedMap)

            globalTokenLocks = acceptedGlobalTokenLocks
              .groupBy(_.value.source)
              .view
              .mapValues(SortedSet.from(_))
              .to(SortedMap)

            allAcceptedSpendTxns = acceptedSpendActions.values.flatten
              .flatMap(spendAction => spendAction.spendTransactions.toList)
              .toList

            globalActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
              SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
            )
            globalActiveTokenLocks = lastSnapshotContext.activeTokenLocks.getOrElse(
              SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
            )

            allTokenLocks: List[Signed[TokenLock]] = globalActiveTokenLocks.values.toList.flatten
            globalActiveTokenLocksByRef <-
              if (allTokenLocks.isEmpty) {
                Async[F].pure(Map.empty[Hash, Signed[TokenLock]])
              } else {
                Stream
                  .emits(allTokenLocks)
                  .covary[F]
                  .chunkN(100)
                  .parEvalMap(10) { chunk =>
                    Async[F].cede *> chunk.toList.traverse { tokenLock =>
                      tokenLock.toHashed.map(hashed => hashed.hash -> tokenLock)
                    } <* Async[F].cede
                  }
                  .compile
                  .toList
                  .flatMap(results => Async[F].cede.as(results.flatten.toMap))
              }

            globalLastAllowSpendRefs = lastSnapshotContext.lastAllowSpendRefs.getOrElse(
              SortedMap.empty[Address, AllowSpendReference]
            )
            globalLastTokenLockRefs = lastSnapshotContext.lastTokenLockRefs.getOrElse(
              SortedMap.empty[Address, TokenLockReference]
            )

            AllowSpendAcceptanceResult(updatedAllowSpends, allowSpendsDeltas, removedAllowSpendKeys) <- allowSpendStateManager
              .acceptAllowSpends(
                epochProgress,
                activeAllowSpendsFromCurrencySnapshots,
                globalAllowSpends,
                globalActiveAllowSpends,
                allAcceptedSpendTxns
              )

            updatedAllowSpendRefs = allowSpendStateManager.acceptAllowSpendRefs(
              globalLastAllowSpendRefs,
              allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
            )

            (updatedBalancesByAllowSpends, updatedBalancesByAllowSpendsDeltas) <- Async[F].fromEither(
              allowSpendStateManager
                .updateGlobalBalancesByAllowSpends(
                  epochProgress,
                  updatedBalancesByRewards,
                  globalAllowSpends,
                  globalActiveAllowSpends
                )
                .leftMap(ex => new RuntimeException(s"Balance arithmetic error updating balances by allow spends: $ex"))
            )

            unexpiredNodeCollateralsRaw = nodeCollateralStateManager.acceptNodeCollaterals(
              lastSnapshotContext,
              epochProgress,
              withdrawalTimeLimit
            )
            (unexpiredCreate, unexpiredWithdraw, _) = unexpiredNodeCollateralsRaw

            updatedCreateNodeCollaterals <- nodeCollateralStateManager.getUpdatedCreateNodeCollaterals(
              nodeCollateralAcceptanceResult,
              unexpiredCreate
            )

            updatedWithdrawNodeCollaterals <- nodeCollateralStateManager.getUpdatedWithdrawNodeCollaterals(
              nodeCollateralAcceptanceResult,
              unexpiredWithdraw,
              lastSnapshotContext
            )

            generatedTokenUnlocks <- tokenLockStateManager
              .generateTokenUnlocks(
                initialData.existingStakes.expired,
                acceptedGlobalTokenLocks,
                globalActiveTokenLocksByRef
              )
              .leftMap(error => new RuntimeException(s"Error generating token unlocks: $error"))
              .liftTo[F]

            TokenLockAcceptanceResult(updatedGlobalTokenLocks, tokenLocksDeltas, removedTokenLockKeys) <- tokenLockStateManager
              .acceptTokenLocks(
                epochProgress,
                globalTokenLocks,
                globalActiveTokenLocks,
                generatedTokenUnlocks
              )

            updatedTokenLockRefs = tokenLockStateManager.acceptTokenLockRefs(
              globalLastTokenLockRefs,
              tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs
            )

            TokenLockBalanceResult(updatedTokenLockBalances, tokenLockBalancesDeltas, removedTokenLockBalanceKeys) = tokenLockStateManager
              .updateTokenLockBalances(
                currencySnapshots,
                lastSnapshotContext.tokenLockBalances
              )

            (updatedBalancesByTokenLocks, updatedBalancesByTokenLocksDeltas) = tokenLockStateManager.updateGlobalBalancesByTokenLocks(
              epochProgress,
              updatedBalancesByAllowSpends,
              globalTokenLocks,
              globalActiveTokenLocks,
              generatedTokenUnlocks
            ) match {
              case Right(balances) => balances
              case Left(error)     => throw new RuntimeException(s"Balance arithmetic error updating balances by token locks: $error")
            }

            lastActiveGlobalAllowSpends = globalActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

            combined = (globalAllowSpends |+| lastActiveGlobalAllowSpends).toList
            allGlobalAllowSpends <-
              if (combined.isEmpty) {
                Async[F].pure(SortedMap.empty[Address, List[Hashed[AllowSpend]]])
              } else {
                Stream
                  .emits(combined)
                  .covary[F]
                  .chunkN(50)
                  .parEvalMap(10) { chunk =>
                    Async[F].cede *> chunk.toList.traverse {
                      case (address, allowSpends) =>
                        val allowSpendsList = allowSpends.toList
                        if (allowSpendsList.isEmpty) {
                          Async[F].pure((address, List.empty[Hashed[AllowSpend]]))
                        } else {
                          Stream
                            .emits(allowSpendsList)
                            .covary[F]
                            .chunkN(20)
                            .parEvalMap(5) { innerChunk =>
                              Async[F].cede *> innerChunk.toList.traverse { (allowSpend: Signed[AllowSpend]) =>
                                allowSpend.toHashed: F[Hashed[AllowSpend]]
                              } <* Async[F].cede
                            }
                            .compile
                            .toList
                            .map { (lists: List[List[Hashed[AllowSpend]]]) =>
                              (address, lists.flatten)
                            }
                        }
                    }: F[List[(Address, List[Hashed[AllowSpend]])]]
                  }
                  .compile
                  .toList
                  .flatMap { (lists: List[List[(Address, List[Hashed[AllowSpend]])]]) =>
                    Async[F].cede.as(lists.flatten.toSortedMap)
                  }
              }

            globalSpendTransactions = acceptedSpendActions.flatMap {
              case (_, spendActions) =>
                spendActions
                  .flatMap(_.spendTransactions.toList)
                  .filter(_.currencyId.isEmpty)
            }.toList

            (updatedBalancesBySpendTransactions, updatedBalancesBySpendTransactionsDeltas) = spendTransactionBalanceManager
              .updateGlobalBalancesBySpendTransactions(
                updatedBalancesByTokenLocks,
                allGlobalAllowSpends,
                globalSpendTransactions
              ) match {
              case Right(balances) => balances
              case Left(error) => throw new RuntimeException(s"Balance arithmetic error updating balances by spend transactions: $error")
            }

            MerkleTreeResult(_, updatedLastCurrencySnapshotProofs) <- buildMerkleTreeAndProofs(
              ordinal,
              updatedLastCurrencySnapshots
            )

            // Clean state maps and compute removed keys in a single pass
            cleanedMapsResult = cleanStateMaps(
              updatedAllowSpends,
              updatedTokenLockBalances,
              updatedGlobalTokenLocks,
              updatedCreateDelegatedStakes,
              updatedWithdrawDelegatedStakes,
              updatedCreateNodeCollaterals,
              updatedWithdrawNodeCollaterals,
              // Previous state for computing removed keys
              lastSnapshotContext.activeDelegatedStakes.getOrElse(SortedMap.empty),
              lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(SortedMap.empty),
              lastSnapshotContext.activeNodeCollaterals.getOrElse(SortedMap.empty),
              lastSnapshotContext.nodeCollateralWithdrawals.getOrElse(SortedMap.empty)
            )

            updatedAllowSpendsCleaned = cleanedMapsResult.cleanedAllowSpends
            updatedTokenLockBalancesCleaned = cleanedMapsResult.cleanedTokenLockBalances
            updatedGlobalTokenLocksCleaned = cleanedMapsResult.cleanedGlobalTokenLocks
            updatedCreateDelegatedStakesCleaned = cleanedMapsResult.cleanedCreateDelegatedStakes
            updatedWithdrawDelegatedStakesCleaned = cleanedMapsResult.cleanedWithdrawDelegatedStakes
            updatedCreateNodeCollateralsCleaned = cleanedMapsResult.cleanedCreateNodeCollaterals
            updatedWithdrawNodeCollateralsCleaned = cleanedMapsResult.cleanedWithdrawNodeCollaterals
            removedDelegatedStakeKeys = cleanedMapsResult.removedDelegatedStakeKeys
            removedDelegatedStakeWithdrawalKeys = cleanedMapsResult.removedDelegatedStakeWithdrawalKeys
            removedNodeCollateralKeys = cleanedMapsResult.removedNodeCollateralKeys
            removedNodeCollateralWithdrawalKeys = cleanedMapsResult.removedNodeCollateralWithdrawalKeys

            updatedPriceState <- priceStateUpdater.updatePriceState(
              lastSnapshotContext.priceState.getOrElse(SortedMap.empty),
              acceptedPricingUpdates,
              epochProgress
            )

            MetagraphSyncAcceptanceResult(updatedAcceptedMetagraphSyncData, metagraphSyncDataDeltas) <- metagraphSyncManager
              .acceptMetagraphSyncData(
                lastSnapshotContext,
                incomingCurrencySnapshots,
                globalSnapshotsProcessed,
                acceptedSpendActions,
                ordinal,
                epochProgress
              )

            gsi = buildGlobalSnapshotInfo(
              ordinal,
              tessellation3MigrationStartingOrdinal,
              tessellation301MigrationStartingOrdinal,
              metagraphSyncDataStartingOrdinal,
              lastSnapshotContext,
              initialData.blockResult,
              updatedLastStateChannelSnapshotHashes,
              transactionsRefs,
              updatedBalancesBySpendTransactions,
              updatedLastCurrencySnapshots,
              updatedLastCurrencySnapshotProofs,
              updatedAllowSpendsCleaned,
              updatedGlobalTokenLocksCleaned,
              updatedTokenLockBalancesCleaned,
              updatedAllowSpendRefs,
              updatedTokenLockRefs,
              updatedUpdateNodeParameters,
              updatedCreateDelegatedStakesCleaned,
              updatedWithdrawDelegatedStakesCleaned,
              updatedCreateNodeCollateralsCleaned,
              updatedWithdrawNodeCollateralsCleaned,
              updatedPriceState,
              updatedAcceptedMetagraphSyncData
            )

            balanceChanges: SortedMap[Address, Balance] =
              initialData.blockResult.contextUpdate.balances.toSortedMap ++
                currencyAcceptanceBalanceUpdate.toSortedMap ++
                rewardBalancesDelta ++
                updatedBalancesByAllowSpendsDeltas ++
                updatedBalancesByTokenLocksDeltas ++
                updatedBalancesBySpendTransactionsDeltas

            currencySnapshotsDeltas = incomingCurrencySnapshots.collect {
              case (address, snapshots) if snapshots.nonEmpty => address -> snapshots.last
            }

            currencySnapshotsProofsDeltas = updatedLastCurrencySnapshotProofs.filter {
              case (address, _) => currencySnapshotsDeltas.contains(address)
            }

            stateChangesAccumulator = StateChangesAccumulator(
              lastStateChannelSnapshotHashes = sCSnapshotHashes.toSortedMap,
              lastTxRefs = transactionsRefsDeltas,
              balances = balanceChanges,
              lastCurrencySnapshots = currencySnapshotsDeltas,
              lastCurrencySnapshotsProofs = currencySnapshotsProofsDeltas,
              activeAllowSpends = allowSpendsDeltas,
              activeTokenLocks = tokenLocksDeltas,
              tokenLockBalances = tokenLockBalancesDeltas,
              lastAllowSpendRefs = allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs.toSortedMap,
              lastTokenLockRefs = tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs.toSortedMap,
              activeDelegatedStakes = updatedCreateDelegatedStakesCleaned,
              delegatedStakesWithdrawals = updatedWithdrawDelegatedStakesCleaned,
              activeNodeCollaterals = updatedCreateNodeCollateralsCleaned,
              nodeCollateralWithdrawals = updatedWithdrawNodeCollateralsCleaned,
              metagraphSyncData = metagraphSyncDataDeltas,
              removedAllowSpendKeys = removedAllowSpendKeys,
              removedTokenLockKeys = removedTokenLockKeys,
              removedTokenLockBalanceKeys = removedTokenLockBalanceKeys,
              removedDelegatedStakeKeys = removedDelegatedStakeKeys,
              removedDelegatedStakeWithdrawalKeys = removedDelegatedStakeWithdrawalKeys,
              removedNodeCollateralKeys = removedNodeCollateralKeys,
              removedNodeCollateralWithdrawalKeys = removedNodeCollateralWithdrawalKeys
            )

            _ <- mptStore.syncFromStateChanges(stateChangesAccumulator, ordinal)
            // Use fromProducer directly since we just synced - avoids cache lookup overhead
            stateProof <- GlobalSnapshotStateProof.fromProducer(mptStore.underlying)

            (expiredAllowSpends, expiredTokenLocks) = (
              allowSpendStateManager.filterExpiredAllowSpends(
                lastActiveGlobalAllowSpends,
                epochProgress
              ),
              tokenLockStateManager.filterExpiredTokenLocks(globalActiveTokenLocks, epochProgress)
            )

            artifactsFromExpired <- artifactEmissionManager.emitAllExpiredArtifacts(
              expiredAllowSpends,
              expiredTokenLocks
            )

            allowSpendsExpiredEvents = artifactsFromExpired.collect { case a: AllowSpendExpiration => a }
            tokenUnlocksEvents = artifactsFromExpired.collect { case t: TokenUnlock => t }

            generatedTokenUnlockArtifacts = SortedSet.from[SharedArtifact](
              generatedTokenUnlocks.view.values.flatten
                .filterNot(x =>
                  tokenUnlocksEvents.exists {
                    case t: TokenUnlock => t.tokenLockRef == x.tokenLockRef
                    case _              => false
                  }
                )
            )
          } yield
            (
              initialData.blockResult,
              allowSpendBlockAcceptanceResult,
              tokenLockBlockAcceptanceResult,
              initialData.delegatedResult,
              nodeCollateralAcceptanceResult,
              scSnapshots,
              returnedSCEvents,
              acceptedRewardTxs,
              gsi,
              stateProof,
              acceptedSpendActions,
              updatedUpdateNodeParameters.view.mapValues(_._1).toSortedMap,
              (allowSpendsExpiredEvents ++ tokenUnlocksEvents ++ generatedTokenUnlockArtifacts).toSortedSet,
              delegatorRewardsMap
            )
        }
      }
    }
  }
}
