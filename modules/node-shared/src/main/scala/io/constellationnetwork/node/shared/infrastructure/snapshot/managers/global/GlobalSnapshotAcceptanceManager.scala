package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import java.lang.management.ManagementFactory

import cats.data.NonEmptyList
import cats.effect.{Async, Sync}
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

import scala.collection.MapView
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.crypto._
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
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.SpendActionValidationError
import io.constellationnetwork.node.shared.domain.swap.block.{AllowSpendBlockAcceptanceManager, AllowSpendBlockAcceptanceResult}
import io.constellationnetwork.node.shared.domain.tokenlock.block.{TokenLockBlockAcceptanceManager, TokenLockBlockAcceptanceResult}
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
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
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._
import monocle.syntax.all._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

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

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: Parallel: HasherSelector: SecurityProvider](
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
    withdrawalTimeLimit: EpochProgress
  ) = {
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
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
      private def acceptInitialData(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        blocksForAcceptance: List[Signed[Block]],
        cdsEvents: List[Signed[UpdateDelegatedStake.Create]],
        wdsEvents: List[Signed[UpdateDelegatedStake.Withdraw]],
        unpEvents: List[Signed[UpdateNodeParameters]],
        lastSnapshotContext: GlobalSnapshotInfo,
        lastActiveTips: SortedSet[ActiveTip],
        lastDeprecatedTips: SortedSet[DeprecatedTip]
      )(
        implicit hasher: Hasher[F]
      ): F[(BlockAcceptanceResult, UpdateDelegatedStakeAcceptanceResult, SortedMap[Id, Signed[UpdateNodeParameters]])] =
        for {
          result <- (
            blockAcceptanceCoordinatorManager.acceptBlocks(
              blocksForAcceptance,
              lastSnapshotContext,
              lastActiveTips,
              lastDeprecatedTips,
              ordinal
            ),
            updateDelegatedStakeAcceptanceManager.accept(
              cdsEvents,
              wdsEvents,
              lastSnapshotContext,
              epochProgress,
              ordinal
            ),
            updateNodeParametersAcceptanceManager
              .acceptUpdateNodeParameters(unpEvents, lastSnapshotContext)
              .map(acceptanceResult =>
                acceptanceResult.accepted.flatMap(signed => signed.proofs.toList.map(proof => (proof.id, signed))).toSortedMap
              )
          ).parTupled
        } yield result

      private def acceptNodeCollateral(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        cncEvents: List[Signed[UpdateNodeCollateral.Create]],
        wncEvents: List[Signed[UpdateNodeCollateral.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        delegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult
      )(implicit hasher: Hasher[F]): F[UpdateNodeCollateralAcceptanceResult] =
        for {
          result <- updateNodeCollateralAcceptanceManager.accept(
            cncEvents,
            wncEvents,
            lastSnapshotContext,
            epochProgress,
            ordinal,
            delegatedStakeAcceptanceResult
          )
        } yield result

      private def processStateChannelEvents(
        ordinal: SnapshotOrdinal,
        lastSnapshotContext: GlobalSnapshotInfo,
        updatedGlobalBalances: SortedMap[Address, Balance],
        scEvents: List[StateChannelOutput],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[StateChannelAcceptanceResult] =
        for {
          result <- stateChannelEventsProcessor.process(
            ordinal,
            lastSnapshotContext.copy(balances = updatedGlobalBalances),
            scEvents,
            validationType,
            getGlobalSnapshotByOrdinal
          )
        } yield result

      private def calculateRewards(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        tessellation3MigrationStartingOrdinal: SnapshotOrdinal,
        acceptedTransactions: SortedSet[Signed[Transaction]],
        delegatedStakeAcceptanceResult: UpdateDelegatedStakeAcceptanceResult,
        unexpiredStakes: (
          SortedMap[Address, SortedSet[DelegatedStakeRecord]],
          SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
          SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
        ),
        calculateRewardsFn: RewardsInput => F[DelegatedRewardsResult]
      )(implicit hasher: Hasher[F]): F[DelegatedRewardsResult] = {
        val (unexpiredCreateDelegatedStakes, unexpiredWithdrawalsDelegatedStaking, expiredWithdrawalsDelegatedStaking) = unexpiredStakes

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
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        spendActions: Map[Address, List[SpendAction]],
        pricingUpdates: Map[Address, List[PricingUpdate]],
        lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        currencyBalances: Map[Option[Address], SortedMap[Address, Balance]],
        globalBalances: Map[Option[Address], SortedMap[Address, Balance]],
        lastSnapshotContext: GlobalSnapshotInfo
      )(implicit hasher: Hasher[F]): F[
        (
          (Map[Address, List[SpendAction]], Map[Address, (SpendAction, List[SpendActionValidationError])]),
          (List[PricingUpdate], List[(PricingUpdate, List[PricingUpdateValidationError])])
        )
      ] =
        for {
          result <- (
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
          ).parTupled
        } yield result

      private def acceptAllowSpendAndTokenLockBlocks(
        ordinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
        tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
        lastSnapshotContext: GlobalSnapshotInfo,
        fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[(AllowSpendBlockAcceptanceResult, TokenLockBlockAcceptanceResult)] =
        for {
          result <- (
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
        } yield result

      private def buildMerkleTreeAndProofs(
        ordinal: SnapshotOrdinal,
        updatedLastCurrencySnapshots: SortedMap[Address, Either[Signed[
          CurrencySnapshot
        ], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
      )(implicit hasher: Hasher[F]): F[(Option[io.constellationnetwork.merkletree.MerkleTree], SortedMap[Address, Proof])] =
        for {
          merkleStartTime <- Async[F].delay(System.currentTimeMillis())

          result <- hasher.getLogic(ordinal) match {
            case JsonHash =>
              val maybeMerkleTree = updatedLastCurrencySnapshots.merkleTree[F]

              val updatedLastCurrencySnapshotProofs = maybeMerkleTree.flatMap {
                _.traverse { merkleTree =>
                  updatedLastCurrencySnapshots.toList.parTraverse {
                    case (address, state) =>
                      (address, state).hash
                        .flatMap(merkleTree.findPath[F](_))
                        .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                        .map((address, _))
                  }
                }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))
              }

              (maybeMerkleTree, updatedLastCurrencySnapshotProofs).tupled

            case KryoHash =>
              val updatedLastCurrencySnapshotsCompatible = updatedLastCurrencySnapshots.map {
                case (address, Left(snapshot)) => (address, Left(snapshot))
                case (address, Right((Signed(incrementalSnapshot, proofs), info))) =>
                  (
                    address,
                    Right(
                      (
                        Signed(CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(incrementalSnapshot), proofs),
                        CurrencySnapshotInfoV1.fromCurrencySnapshotInfo(info)
                      )
                    )
                  )
              }

              val maybeMerkleTree = updatedLastCurrencySnapshotsCompatible.merkleTree[F]
              val updatedLastCurrencySnapshotProofs = maybeMerkleTree.flatMap {
                _.traverse { merkleTree =>
                  updatedLastCurrencySnapshotsCompatible.toList.parTraverse {
                    case (address, state) =>
                      hasher
                        .hash((address, state))
                        .flatMap(merkleTree.findPath[F](_))
                        .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                        .map((address, _))
                  }
                }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))
              }

              (maybeMerkleTree, updatedLastCurrencySnapshotProofs).tupled
          }

          merkleEndTime <- Async[F].delay(System.currentTimeMillis())
          _ <- logger.info(
            s"--- [ORDINAL=$ordinal] Merkle tree operations completed in ${merkleEndTime - merkleStartTime}ms (parallelized)"
          )

        } yield result

      private def cleanStateMaps(
        ordinal: SnapshotOrdinal,
        updatedAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        updatedTokenLockBalances: SortedMap[Address, SortedMap[Address, Balance]],
        updatedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
        updatedCreateDelegatedStakes: SortedMap[Address, SortedSet[DelegatedStakeRecord]],
        updatedWithdrawDelegatedStakes: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
        updatedCreateNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]],
        updatedWithdrawNodeCollaterals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]
      ): F[
        (
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
          SortedMap[Address, SortedMap[Address, Balance]],
          SortedMap[Address, SortedSet[Signed[TokenLock]]],
          SortedMap[Address, SortedSet[DelegatedStakeRecord]],
          SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
          SortedMap[Address, SortedSet[NodeCollateralRecord]],
          SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]
        )
      ] =
        for {
          result <- (
            Async[F].pure(
              updatedAllowSpends.map {
                case (outerKey, innerMap) =>
                  val cleanedInnerMap = innerMap.filter { case (_, allowSpendSet) => allowSpendSet.nonEmpty }
                  (outerKey, cleanedInnerMap)
              }.filter { case (_, innerMap) => innerMap.nonEmpty }
            ),
            Async[F].pure(updatedTokenLockBalances.filter { case (_, tokenLockBalances) => tokenLockBalances.nonEmpty }),
            Async[F].pure(updatedGlobalTokenLocks.filter { case (_, tokenLocks) => tokenLocks.nonEmpty }),
            Async[F].pure(updatedCreateDelegatedStakes.filter {
              case (_, createDelegatedStakeRecords) => createDelegatedStakeRecords.nonEmpty
            }),
            Async[F].pure(updatedWithdrawDelegatedStakes.filter {
              case (_, updatedDelegatedStakeRecords) => updatedDelegatedStakeRecords.nonEmpty
            }),
            Async[F].pure(updatedCreateNodeCollaterals.filter {
              case (_, createNodeCollateralsRecords) => createNodeCollateralsRecords.nonEmpty
            }),
            Async[F].pure(updatedWithdrawNodeCollaterals.filter {
              case (_, updatedNodeCollateralsRecords) => updatedNodeCollateralsRecords.nonEmpty
            })
          ).parTupled
        } yield result

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

        for {
          _ <- logger.debug(s"--- [ORDINAL=$ordinal] accept started")
          _ <- logger.debug(
            s"--- [ORDINAL=$ordinal] Active threads: ${Thread.activeCount()}, Current thread: ${Thread.currentThread().getName}"
          )
          _ <- Async[F].delay {
            val runtime = Runtime.getRuntime
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            logger.info(s"--- [ORDINAL=$ordinal] Memory usage: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB")
          }

          (acceptanceResult, delegatedStakeAcceptanceResult, acceptedUpdateNodeParametersTemp) <-
            acceptInitialData(
              ordinal,
              epochProgress,
              blocksForAcceptance,
              cdsEvents,
              wdsEvents,
              unpEvents,
              lastSnapshotContext,
              lastActiveTips,
              lastDeprecatedTips
            )

          nodeCollateralAcceptanceResult <- acceptNodeCollateral(
            ordinal,
            epochProgress,
            cncEvents,
            wncEvents,
            lastSnapshotContext,
            delegatedStakeAcceptanceResult
          )

          updatedUpdateNodeParameters = lastSnapshotContext.updateNodeParameters.getOrElse(
            SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]
          ) ++ acceptedUpdateNodeParametersTemp.view.mapValues(unp => (unp, ordinal))

          acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet
          updatedGlobalBalances = lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances

          _ <- {
            val runtime = Runtime.getRuntime
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory.toDouble / maxMemory.toDouble) * 100

            logger.info(
              s"--- [ORDINAL=$ordinal] Memory usage: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB (${memoryUsagePercent.toInt}%)"
            ) >>
              logger.info(s"--- [ORDINAL=$ordinal] Balance entries: ${updatedGlobalBalances.size}") >>
              logger.info(s"--- [ORDINAL=$ordinal] Currency snapshots: ${lastSnapshotContext.lastCurrencySnapshots.size}") >>
              logger.info(s"--- [ORDINAL=$ordinal] Allow spends: ${lastSnapshotContext.activeAllowSpends.map(_.size).getOrElse(0)}") >>
              logger.info(s"--- [ORDINAL=$ordinal] Token locks: ${lastSnapshotContext.activeTokenLocks.map(_.size).getOrElse(0)}") >>
              logger.info(s"--- [ORDINAL=$ordinal] State channel snapshots: ${lastSnapshotContext.lastStateChannelSnapshotHashes.size}") >>
              logger.info(s"--- [ORDINAL=$ordinal] Transaction refs: ${lastSnapshotContext.lastTxRefs.size}")
          }

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

          (
            transactionsRefs,
            unexpiredStakes,
            currencyBalances,
            sharedArtifacts,
            sCSnapshotHashes
          ) <- (
            Async[F].pure(
              transactionReferenceManager.acceptTransactionRefs(
                lastSnapshotContext.lastTxRefs,
                acceptanceResult.contextUpdate.lastTxRefs,
                acceptedTransactions
              )
            ),
            Async[F].pure(
              delegatedStakeStateManager.acceptDelegatedStakes(lastSnapshotContext, epochProgress, withdrawalTimeLimit)
            ),
            Async[F].pure(
              currencySnapshots.toList.map {
                case (_, Left(_))              => Map.empty[Option[Address], SortedMap[Address, Balance]]
                case (address, Right((_, si))) => Map(address.some -> si.balances)
              }
                .foldLeft(Map.empty[Option[Address], SortedMap[Address, Balance]])(_ ++ _)
            ),
            Async[F].pure(
              incomingCurrencySnapshots.toList.map {
                case (address, snapshots) =>
                  val artifacts: List[SharedArtifact] = snapshots.flatMap {
                    case Left(_)       => Nil
                    case Right((s, _)) => s.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).toList
                  }
                  Map(address -> artifacts)
              }
                .foldLeft(Map.empty[Address, List[SharedArtifact]])(_ |+| _)
                .view
            ),
            scSnapshots.toList.traverse {
              case (address, nel) => nel.last.toHashed.map(address -> _.hash)
            }
              .map(_.toMap)
          ).parTupled

          (_, _, expiredWithdrawalsDelegatedStaking) = unexpiredStakes

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
            delegatedStakeAcceptanceResult,
            unexpiredStakes,
            calculateRewardsFn
          )

          (updatedBalancesByRewards, acceptedRewardTxs) = rewardAcceptanceManager.acceptRewardTxs(
            updatedGlobalBalances ++ currencyAcceptanceBalanceUpdate,
            withdrawalRewardTxs ++ nodeOperatorRewards ++ reservedAddressRewards
          )

          globalBalances = Map(none[Address] -> updatedBalancesByRewards)

          (spendActions, pricingUpdates, globalSnapshotsProcessed) <- (
            Async[F].pure(
              sharedArtifacts
                .mapValues(_.collect { case sa: SpendAction => sa })
                .filter { case (_, actions) => actions.nonEmpty }
                .toMap
            ),
            Async[F].pure(
              sharedArtifacts
                .mapValues(_.collect { case pu: PricingUpdate => pu })
                .filter { case (_, updates) => updates.nonEmpty }
                .toMap
            ),
            Async[F].pure(
              sharedArtifacts.view
                .mapValues(_.collect { case pu: GlobalSnapshotsProcessed => pu })
                .filter { case (_, updates) => updates.nonEmpty }
                .toMap
            )
          ).parTupled

          lastActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
            SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
          )

          (
            (acceptedSpendActions, rejectedSpendActions),
            (acceptedPricingUpdates, rejectedPricingUpdates)
          ) <- validateArtifacts(
            ordinal,
            epochProgress,
            spendActions,
            pricingUpdates,
            lastActiveAllowSpends,
            currencyBalances,
            globalBalances,
            lastSnapshotContext
          )

          _ <- logger.debug(s"--- [ORDINAL=$ordinal] Accepted spend actions: ${acceptedSpendActions.show}")
          _ <- logger.debug(s"--- [ORDINAL=$ordinal] Rejected spend actions: ${rejectedSpendActions.show}")
          _ <- logger.debug(s"--- [ORDINAL=$ordinal] Accepted pricing updates: ${acceptedPricingUpdates.show}")
          _ <- logger.debug(s"--- [ORDINAL=$ordinal] Rejected pricing updates: ${rejectedPricingUpdates.show}")

          updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
          updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

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
          acceptedGlobalTokenLocks = tokenLockBlockAcceptanceResult.accepted.flatMap(_.value.tokenLocks.toList)

          activeAllowSpendsFromCurrencySnapshots = currencySnapshots
            .mapFilter(_.toOption.flatMap { case (_, info) => info.activeAllowSpends })

          (globalAllowSpends, globalTokenLocks, allAcceptedSpendTxns) <- (
            Async[F].pure(
              acceptedGlobalAllowSpends
                .groupBy(_.value.source)
                .view
                .mapValues(SortedSet.from(_))
                .to(SortedMap)
            ),
            Async[F].pure(
              acceptedGlobalTokenLocks
                .groupBy(_.value.source)
                .view
                .mapValues(SortedSet.from(_))
                .to(SortedMap)
            ),
            Async[F].pure(
              acceptedSpendActions.values.flatten
                .flatMap(spendAction => spendAction.spendTransactions.toList)
                .toList
            )
          ).parTupled

          globalActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
            SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
          )
          globalActiveTokenLocks = lastSnapshotContext.activeTokenLocks.getOrElse(
            SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
          )

          globalActiveTokenLocksByRef <- globalActiveTokenLocks.values.toList.flatten.parTraverse { tokenLock =>
            tokenLock.toHashed.map(hashed => hashed.hash -> tokenLock)
          }.map(_.toMap)

          globalLastAllowSpendRefs = lastSnapshotContext.lastAllowSpendRefs.getOrElse(
            SortedMap.empty[Address, AllowSpendReference]
          )
          globalLastTokenLockRefs = lastSnapshotContext.lastTokenLockRefs.getOrElse(
            SortedMap.empty[Address, TokenLockReference]
          )

          updatedAllowSpends <- allowSpendStateManager.acceptAllowSpends(
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

          updatedBalancesByAllowSpends <- Async[F].fromEither(
            allowSpendStateManager
              .updateGlobalBalancesByAllowSpends(
                epochProgress,
                updatedBalancesByRewards,
                globalAllowSpends,
                globalActiveAllowSpends
              )
              .leftMap(ex => new RuntimeException(s"Balance arithmetic error updating balances by allow spends: $ex"))
          )

          (_, updatedCreateNodeCollaterals, updatedWithdrawNodeCollaterals) <- (
            Async[F].pure(
              nodeCollateralStateManager.acceptNodeCollaterals(
                lastSnapshotContext,
                epochProgress,
                withdrawalTimeLimit
              )
            ),
            Async[F].pure(()).flatMap { _ =>
              val (unexpiredCreate, _, _) = nodeCollateralStateManager.acceptNodeCollaterals(
                lastSnapshotContext,
                epochProgress,
                withdrawalTimeLimit
              )
              nodeCollateralStateManager.getUpdatedCreateNodeCollaterals(
                nodeCollateralAcceptanceResult,
                unexpiredCreate
              )
            },
            Async[F].pure(()).flatMap { _ =>
              val (_, unexpiredWithdraw, _) = nodeCollateralStateManager.acceptNodeCollaterals(
                lastSnapshotContext,
                epochProgress,
                withdrawalTimeLimit
              )
              nodeCollateralStateManager.getUpdatedWithdrawNodeCollaterals(
                nodeCollateralAcceptanceResult,
                unexpiredWithdraw,
                lastSnapshotContext
              )
            }
          ).parTupled

          generatedTokenUnlocks = tokenLockStateManager.generateTokenUnlocks(
            expiredWithdrawalsDelegatedStaking,
            globalActiveTokenLocksByRef
          ) match {
            case Right(tokenUnlocks) => tokenUnlocks
            case Left(error)         => throw new RuntimeException(s"Error when generating token unlocks: $error")
          }

          updatedGlobalTokenLocks <- tokenLockStateManager.acceptTokenLocks(
            epochProgress,
            globalTokenLocks,
            globalActiveTokenLocks,
            generatedTokenUnlocks
          )

          (updatedTokenLockRefs, updatedTokenLockBalances) <- (
            Async[F].pure(
              tokenLockStateManager.acceptTokenLockRefs(
                globalLastTokenLockRefs,
                tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs
              )
            ),
            Async[F].pure(
              tokenLockStateManager.updateTokenLockBalances(
                currencySnapshots,
                lastSnapshotContext.tokenLockBalances
              )
            )
          ).parTupled

          updatedBalancesByTokenLocks = tokenLockStateManager.updateGlobalBalancesByTokenLocks(
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

          hashStartTime <- Async[F].delay(System.nanoTime())
          allGlobalAllowSpends <- (globalAllowSpends |+| lastActiveGlobalAllowSpends).toList.parTraverse {
            case (address, allowSpends) =>
              allowSpends.toList.parTraverse(_.toHashed).map(address -> _)
          }.map(_.toSortedMap)
          hashEndTime <- Async[F].delay(System.nanoTime())
          hashDuration = (hashEndTime - hashStartTime) / 1_000_000
          _ <- logger.info(s"--- [ORDINAL=$ordinal] allGlobalAllowSpends hashed in ${hashDuration}ms (parallelized)")

          globalSpendTransactions = acceptedSpendActions.flatMap {
            case (_, spendActions) =>
              spendActions
                .flatMap(_.spendTransactions.toList)
                .filter(_.currencyId.isEmpty)
          }.toList

          _ <- logger.debug(s"--- [ORDINAL=$ordinal] Processing ${globalSpendTransactions.size} spend transactions")
          _ <- logger.debug(s"--- [ORDINAL=$ordinal] Processing ${allGlobalAllowSpends.size} allow spends")

          spendTxStartTime <- Async[F].delay(System.currentTimeMillis())
          updatedBalancesBySpendTransactions = spendTransactionBalanceManager.updateGlobalBalancesBySpendTransactions(
            updatedBalancesByTokenLocks,
            allGlobalAllowSpends,
            globalSpendTransactions
          ) match {
            case Right(balances) => balances
            case Left(error)     => throw new RuntimeException(s"Balance arithmetic error updating balances by spend transactions: $error")
          }
          spendTxEndTime <- Async[F].delay(System.currentTimeMillis())

          (maybeMerkleTree, updatedLastCurrencySnapshotProofs) <- buildMerkleTreeAndProofs(
            ordinal,
            updatedLastCurrencySnapshots
          )

          (
            updatedAllowSpendsCleaned,
            updatedTokenLockBalancesCleaned,
            updatedGlobalTokenLocksCleaned,
            updatedCreateDelegatedStakesCleaned,
            updatedWithdrawDelegatedStakesCleaned,
            updatedCreateNodeCollateralsCleaned,
            updatedWithdrawNodeCollateralsCleaned
          ) <- cleanStateMaps(
            ordinal,
            updatedAllowSpends,
            updatedTokenLockBalances,
            updatedGlobalTokenLocks,
            updatedCreateDelegatedStakes,
            updatedWithdrawDelegatedStakes,
            updatedCreateNodeCollaterals,
            updatedWithdrawNodeCollaterals
          )

          updatedPriceState <- priceStateUpdater.updatePriceState(
            lastSnapshotContext.priceState.getOrElse(SortedMap.empty),
            acceptedPricingUpdates,
            epochProgress
          )

          updatedAcceptedMetagraphSyncData <- metagraphSyncManager.acceptMetagraphSyncData(
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
            acceptanceResult,
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

          start <- Sync[F].monotonic
          stateProof <- gsi.stateProof(maybeMerkleTree)
          end <- Sync[F].monotonic
          duration = end - start
          _ <- logger.debug(s"--- [ORDINAL=$ordinal] stateProof took ${duration.toMillis}ms")

          (expiredAllowSpends, expiredTokenLocks) <- (
            Async[F].pure(
              allowSpendStateManager.filterExpiredAllowSpends(
                lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]]),
                epochProgress
              )
            ),
            Async[F].pure(
              tokenLockStateManager.filterExpiredTokenLocks(globalActiveTokenLocks, epochProgress)
            )
          ).parTupled

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
            acceptanceResult,
            allowSpendBlockAcceptanceResult,
            tokenLockBlockAcceptanceResult,
            delegatedStakeAcceptanceResult,
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
