package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshotV1, CurrencySnapshotInfoV1}
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
import io.constellationnetwork.node.shared.domain.priceOracle.{PriceStateUpdater, PricingUpdateValidator}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.block.{
  AllowSpendBlockAcceptanceContext,
  AllowSpendBlockAcceptanceManager,
  AllowSpendBlockAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.tokenlock.block.{
  TokenLockBlockAcceptanceContext,
  TokenLockBlockAcceptanceManager,
  TokenLockBlockAcceptanceResult
}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral._
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
  ) = new GlobalSnapshotAcceptanceManager[F] {

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
      implicit val hasher = HasherSelector[F].getForOrdinal(ordinal)
      val tessellation3MigrationStartingOrdinal = fieldsAddedOrdinals.tessellation3Migration
        .getOrElse(environment, SnapshotOrdinal.MinValue)

      val tessellation301MigrationStartingOrdinal = fieldsAddedOrdinals.tessellation301Migration
        .getOrElse(environment, SnapshotOrdinal.MinValue)

      val metagraphSyncDataStartingOrdinal = fieldsAddedOrdinals.metagraphSyncData
        .getOrElse(environment, SnapshotOrdinal.MinValue)

      for {
        acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips, ordinal)
        delegatedStakeAcceptanceResult <- updateDelegatedStakeAcceptanceManager.accept(
          cdsEvents,
          wdsEvents,
          lastSnapshotContext,
          epochProgress,
          ordinal
        )
        nodeCollateralAcceptanceResult <- updateNodeCollateralAcceptanceManager.accept(
          cncEvents,
          wncEvents,
          lastSnapshotContext,
          epochProgress,
          ordinal,
          delegatedStakeAcceptanceResult
        )
        acceptedUpdateNodeParameters <- updateNodeParametersAcceptanceManager
          .acceptUpdateNodeParameters(unpEvents, lastSnapshotContext)
          .map(acceptanceResult =>
            acceptanceResult.accepted.flatMap(signed => signed.proofs.toList.map(proof => (proof.id, signed))).toSortedMap
          )

        updatedUpdateNodeParameters = lastSnapshotContext.updateNodeParameters.getOrElse(
          SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]
        ) ++ acceptedUpdateNodeParameters.view.mapValues(unp => (unp, ordinal))

        acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet
        updatedGlobalBalances = lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances

        StateChannelAcceptanceResult(
          scSnapshots,
          currencySnapshots,
          returnedSCEvents,
          currencyAcceptanceBalanceUpdate,
          incomingCurrencySnapshots
        ) <-
          stateChannelEventsProcessor
            .process(
              ordinal,
              lastSnapshotContext.copy(balances = updatedGlobalBalances),
              scEvents,
              validationType,
              getGlobalSnapshotByOrdinal
            )

        transactionsRefs = acceptTransactionRefs(
          lastSnapshotContext.lastTxRefs,
          acceptanceResult.contextUpdate.lastTxRefs,
          acceptedTransactions
        )

        (
          unexpiredCreateDelegatedStakes,
          unexpiredWithdrawalsDelegatedStaking,
          expiredWithdrawalsDelegatedStaking
        ) = acceptDelegatedStakes(lastSnapshotContext, epochProgress)

        DelegatedRewardsResult(
          delegatorRewardsMap,
          updatedCreateDelegatedStakes,
          updatedWithdrawDelegatedStakes,
          nodeOperatorRewards,
          reservedAddressRewards,
          withdrawalRewardTxs,
          _
        ) <-
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

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
          updatedGlobalBalances ++ currencyAcceptanceBalanceUpdate,
          withdrawalRewardTxs ++ nodeOperatorRewards ++ reservedAddressRewards
        )

        currencyBalances = currencySnapshots.toList.map {
          case (_, Left(_))              => Map.empty[Option[Address], SortedMap[Address, Balance]]
          case (address, Right((_, si))) => Map(address.some -> si.balances)
        }
          .foldLeft(Map.empty[Option[Address], SortedMap[Address, Balance]])(_ ++ _)

        globalBalances = Map(none[Address] -> updatedBalancesByRewards)

        sharedArtifacts: Map[Address, List[SharedArtifact]] =
          incomingCurrencySnapshots.toList.map {
            case (address, snapshots) =>
              val artifacts: List[SharedArtifact] = snapshots.flatMap {
                case Left(_)       => Nil
                case Right((s, _)) => s.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).toList
              }
              Map(address -> artifacts)
          }
            .foldLeft(Map.empty[Address, List[SharedArtifact]])(_ |+| _)

        spendActions = sharedArtifacts.view
          .mapValues(_.collect { case sa: SpendAction => sa })
          .filter { case (_, actions) => actions.nonEmpty }
          .toMap

        pricingUpdates = sharedArtifacts.view
          .mapValues(_.collect { case pu: PricingUpdate => pu })
          .filter { case (_, updates) => updates.nonEmpty }
          .toMap

        lastActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )

        (acceptedSpendActions, rejectedSpendActions) <- spendActionValidator.validateReturningAcceptedAndRejected(
          spendActions,
          lastActiveAllowSpends,
          currencyBalances ++ globalBalances
        )

        _ <- Slf4jLogger.getLogger[F].debug(s"--- [ORDINAL=$ordinal] Accepted spend actions: ${acceptedSpendActions.show}")
        _ <- Slf4jLogger.getLogger[F].debug(s"--- [ORDINAL=$ordinal] Rejected spend actions: ${rejectedSpendActions.show}")

        (acceptedPricingUpdates, rejectedPricingUpdates) <- pricingUpdateValidator.validateReturningAcceptedAndRejected(
          pricingUpdates,
          lastSnapshotContext,
          epochProgress
        )

        _ <- Slf4jLogger.getLogger[F].debug(s"--- Accepted pricing updates: ${acceptedPricingUpdates.show}")
        _ <- Slf4jLogger.getLogger[F].debug(s"--- Rejected pricing updates: ${rejectedPricingUpdates.show}")

        sCSnapshotHashes <- scSnapshots.toList.traverse {
          case (address, nel) => nel.last.toHashed.map(address -> _.hash)
        }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
        updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

        allowSpendBlockAcceptanceResult <- acceptAllowSpendBlocks(
          allowSpendBlocksForAcceptance,
          lastSnapshotContext,
          ordinal
        )

        tokenLockBlockAcceptanceResult <- acceptTokenLockBlocks(
          tokenLockBlocksForAcceptance,
          lastSnapshotContext,
          ordinal
        )

        acceptedGlobalAllowSpends = allowSpendBlockAcceptanceResult.accepted.flatMap(_.value.transactions.toList)
        acceptedGlobalTokenLocks = tokenLockBlockAcceptanceResult.accepted.flatMap(_.value.tokenLocks.toList)

        activeAllowSpendsFromCurrencySnapshots = incomingCurrencySnapshots.flatMap {
          case (address, snapshots) =>
            snapshots.reverse.collectFirst {
              case Right((_, info)) if info.activeAllowSpends.isDefined =>
                address -> info.activeAllowSpends.get
            }
        }
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

        allAcceptedSpendTxns =
          acceptedSpendActions.values.flatten
            .flatMap(spendAction => spendAction.spendTransactions.toList)
            .toList

        globalActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )
        globalActiveTokenLocks = lastSnapshotContext.activeTokenLocks.getOrElse(
          SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
        )
        globalActiveTokenLocksByRef <- globalActiveTokenLocks.values.toList.flatten.traverse { tokenLock =>
          tokenLock.toHashed.map(hashed => hashed.hash -> tokenLock)
        }.map(_.toMap)

        globalLastAllowSpendRefs = lastSnapshotContext.lastAllowSpendRefs.getOrElse(
          SortedMap.empty[Address, AllowSpendReference]
        )
        globalLastTokenLockRefs = lastSnapshotContext.lastTokenLockRefs.getOrElse(
          SortedMap.empty[Address, TokenLockReference]
        )

        updatedAllowSpends <- acceptAllowSpends(
          epochProgress,
          activeAllowSpendsFromCurrencySnapshots,
          globalAllowSpends,
          globalActiveAllowSpends,
          allAcceptedSpendTxns
        )

        updatedAllowSpendRefs = acceptAllowSpendRefs(
          globalLastAllowSpendRefs,
          allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
        )

        updatedBalancesByAllowSpends <- Async[F].fromEither(
          updateGlobalBalancesByAllowSpends(
            epochProgress,
            updatedBalancesByRewards,
            globalAllowSpends,
            globalActiveAllowSpends
          ).leftMap(ex => new RuntimeException(s"Balance arithmetic error updating balances by allow spends: $ex"))
        )

        (unexpiredCreateNodeCollaterals, unexpiredWithdrawNodeCollaterals, _) = acceptNodeCollaterals(
          lastSnapshotContext,
          epochProgress
        )
        updatedCreateNodeCollaterals <- getUpdatedCreateNodeCollaterals(nodeCollateralAcceptanceResult, unexpiredCreateNodeCollaterals)

        updatedWithdrawNodeCollaterals <- getUpdatedWithdrawNodeCollaterals(
          nodeCollateralAcceptanceResult,
          unexpiredWithdrawNodeCollaterals,
          lastSnapshotContext
        )

        generatedTokenUnlocks = generateTokenUnlocks(
          expiredWithdrawalsDelegatedStaking,
          globalActiveTokenLocksByRef
        ) match {
          case Right(tokenUnlocks) => tokenUnlocks
          case Left(error)         => throw new RuntimeException(s"Error when generating token unlocks: $error")
        }

        updatedGlobalTokenLocks <- acceptTokenLocks(
          epochProgress,
          globalTokenLocks,
          globalActiveTokenLocks,
          generatedTokenUnlocks
        )

        updatedTokenLockRefs = acceptTokenLockRefs(
          globalLastTokenLockRefs,
          tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs
        )

        updatedTokenLockBalances = updateTokenLockBalances(
          incomingCurrencySnapshots,
          lastSnapshotContext.tokenLockBalances
        )

        updatedBalancesByTokenLocks = updateGlobalBalancesByTokenLocks(
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
        allGlobalAllowSpends <- (globalAllowSpends |+| lastActiveGlobalAllowSpends).toList.traverse {
          case (address, allowSpends) =>
            allowSpends.toList.traverse(_.toHashed).map(address -> _)
        }.map(_.toSortedMap)

        globalSpendTransactions = acceptedSpendActions.flatMap {
          case (_, spendActions) =>
            spendActions
              .flatMap(_.spendTransactions.toList)
              .filter(_.currencyId.isEmpty)
        }.toList

        updatedBalancesBySpendTransactions = updateGlobalBalancesBySpendTransactions(
          updatedBalancesByTokenLocks,
          allGlobalAllowSpends,
          globalSpendTransactions
        ) match {
          case Right(balances) => balances
          case Left(error)     => throw new RuntimeException(s"Balance arithmetic error updating balances by spend transactions: $error")
        }

        (maybeMerkleTree, updatedLastCurrencySnapshotProofs) <- hasher.getLogic(ordinal) match {
          case JsonHash =>
            val maybeMerkleTree = updatedLastCurrencySnapshots.merkleTree[F]

            val updatedLastCurrencySnapshotProofs = maybeMerkleTree.flatMap {
              _.traverse { merkleTree =>
                updatedLastCurrencySnapshots.toList.traverse {
                  case (address, state) =>
                    (address, state).hash
                      .map(merkleTree.findPath(_))
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
                updatedLastCurrencySnapshotsCompatible.toList.traverse {
                  case (address, state) =>
                    hasher
                      .hash((address, state))
                      .map(merkleTree.findPath(_))
                      .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                      .map((address, _))
                }
              }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))
            }

            (maybeMerkleTree, updatedLastCurrencySnapshotProofs).tupled
        }

        updatedAllowSpendsCleaned = updatedAllowSpends.map {
          case (outerKey, innerMap) =>
            val cleanedInnerMap = innerMap.filter {
              case (_, allowSpendSet) =>
                allowSpendSet.nonEmpty
            }
            (outerKey, cleanedInnerMap)
        }.filter {
          case (_, innerMap) =>
            innerMap.nonEmpty
        }
        updatedTokenLockBalancesCleaned = updatedTokenLockBalances.filter {
          case (_, tokenLockBalances) =>
            tokenLockBalances.nonEmpty
        }
        updatedGlobalTokenLocksCleaned = updatedGlobalTokenLocks.filter {
          case (_, tokenLocks) =>
            tokenLocks.nonEmpty
        }
        updatedCreateDelegatedStakesCleaned = updatedCreateDelegatedStakes.filter {
          case (_, createDelegatedStakeRecords) =>
            createDelegatedStakeRecords.nonEmpty
        }
        updatedWithdrawDelegatedStakesCleaned = updatedWithdrawDelegatedStakes.filter {
          case (_, updatedDelegatedStakeRecords) =>
            updatedDelegatedStakeRecords.nonEmpty
        }
        updatedCreateNodeCollateralsCleaned = updatedCreateNodeCollaterals.filter {
          case (_, createNodeCollateralsRecords) =>
            createNodeCollateralsRecords.nonEmpty
        }
        updatedWithdrawNodeCollateralsCleaned = updatedWithdrawNodeCollaterals.filter {
          case (_, updatedNodeCollateralsRecords) =>
            updatedNodeCollateralsRecords.nonEmpty
        }

        updatedPriceState <- priceStateUpdater.updatePriceState(
          lastSnapshotContext.priceState.getOrElse(SortedMap.empty),
          acceptedPricingUpdates,
          epochProgress
        )

        updatedAcceptedMetagraphSyncData = acceptMetagraphSyncData(
          lastSnapshotContext,
          incomingCurrencySnapshots,
          acceptedSpendActions,
          ordinal,
          epochProgress
        )

        gsi = GlobalSnapshotInfo(
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

        stateProof <- gsi.stateProof(maybeMerkleTree)

        allowSpendsExpiredEvents <- emitAllowSpendsExpired(
          filterExpiredAllowSpends(
            lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]]),
            epochProgress
          )
        )

        tokenUnlocksEvents <- emitTokenUnlocks(
          filterExpiredTokenLocks(globalActiveTokenLocks, epochProgress)
        )
        generatedTokenUnlockArtifacts = SortedSet.from[SharedArtifact](
          generatedTokenUnlocks.view.values.flatten
            .filterNot(x =>
              tokenUnlocksEvents.exists {
                case t: TokenUnlock => t.tokenLockRef == x.tokenLockRef
                case _              => false
              }
            )
        )

        _ <- Slf4jLogger
          .getLogger[F]
          .debug(
            s"[TokenUnlock][Ordinal=${ordinal.show}][EpochProgress=${epochProgress.show}] Token unlocks events generated: $tokenUnlocksEvents generated token unlocks $generatedTokenUnlockArtifacts"
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
          acceptedUpdateNodeParameters,
          allowSpendsExpiredEvents ++ tokenUnlocksEvents ++ generatedTokenUnlockArtifacts,
          delegatorRewardsMap
        )
    }

    private def getUpdatedCreateNodeCollaterals(
      nodeCollateralAcceptanceResult: UpdateNodeCollateralAcceptanceResult,
      unexpiredCreateNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]]
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[NodeCollateralRecord]]] = {

      val acceptedTokenLockRefs = nodeCollateralAcceptanceResult.acceptedCreates.map {
        case (addr, creates) => (addr, creates.map(_._1.tokenLockRef).toSet)
      }
      val filteredUnexpiredCreateNodeCollaterals = unexpiredCreateNodeCollaterals.map {
        case (addr, creates) =>
          val tokenLocks = acceptedTokenLockRefs.getOrElse(addr, Set.empty)
          (addr, creates.filterNot(c => tokenLocks(c.event.tokenLockRef)))
      }
      val acceptedCreates = nodeCollateralAcceptanceResult.acceptedCreates.map {
        case (addr, cs) => addr -> cs.map(c => NodeCollateralRecord(c._1, c._2)).toSortedSet
      }
      val activeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]] =
        filteredUnexpiredCreateNodeCollaterals |+| acceptedCreates
      // remove withdrawn stakes from the active list
      val withdrawnCollaterals = nodeCollateralAcceptanceResult.acceptedWithdrawals.flatMap(_._2.map(_._1.collateralRef)).toSet
      activeCollaterals.toList.traverse {
        case (addr, records) =>
          records.toList.traverse { record =>
            NodeCollateralReference.of(record.event).map(ref => (record, withdrawnCollaterals(ref.hash)))
          }.map(records => (addr, records.filterNot(_._2).map(_._1).toSortedSet))
      }
        .map(_.filterNot(_._2.isEmpty))
        .map(SortedMap.from(_))
    }

    private def getUpdatedWithdrawNodeCollaterals(
      nodeCollateralAcceptanceResult: UpdateNodeCollateralAcceptanceResult,
      unexpiredWithdrawNodeCollaterals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
      lastSnapshotContext: GlobalSnapshotInfo
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]] =
      nodeCollateralAcceptanceResult.acceptedWithdrawals.toList.traverse {
        case (addr, acceptedWithdrawls) =>
          acceptedWithdrawls.traverse {
            case (ev, ep) =>
              lastSnapshotContext.activeNodeCollaterals
                .flatTraverse(_.get(addr).flatTraverse {
                  _.findM { s =>
                    NodeCollateralReference.of(s.event).map(_.hash === ev.collateralRef)
                  }.map(_.map(rec => PendingNodeCollateralWithdrawal(rec.event, rec.createdAt, ep)))
                })
                .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing node collaterals")))
          }.map(pending => addr -> pending.toSortedSet)
      }.map(SortedMap.from(_))
        .map(unexpiredWithdrawNodeCollaterals |+| _)
        .map(_.filterNot(_._2.isEmpty))

    private def generateTokenUnlocks(
      expiredWithdrawalsDelegatedStaking: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
      globalActiveTokenLocksByRef: Map[Hash, Signed[TokenLock]]
    ): Either[DelegatedStakeError, Map[Address, List[TokenUnlock]]] =
      expiredWithdrawalsDelegatedStaking.toList.traverse {
        case (address, withdrawals) =>
          withdrawals.toList.traverse {
            case PendingDelegatedStakeWithdrawal(delegatedStaking, _, _, _) =>
              for {
                activeTokenLock <- globalActiveTokenLocksByRef
                  .get(delegatedStaking.tokenLockRef)
                  .toRight(MissingTokenLock(s"Missing TokenLock for tokenLockRef: ${delegatedStaking.tokenLockRef}"))
              } yield
                TokenUnlock(
                  delegatedStaking.tokenLockRef,
                  activeTokenLock.amount,
                  activeTokenLock.currencyId,
                  activeTokenLock.source
                )
          }.map(tokenUnlocks => address -> tokenUnlocks)
      }.map(_.toMap)

    private def acceptDelegatedStakes(
      lastSnapshotContext: GlobalSnapshotInfo,
      epochProgress: EpochProgress
    ): (
      SortedMap[Address, SortedSet[DelegatedStakeRecord]],
      SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
      SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
    ) = {
      val existingDelegatedStakes = lastSnapshotContext.activeDelegatedStakes.getOrElse(
        SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]]
      )

      val existingWithdrawals = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
        SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
      )

      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      val unexpiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filterNot {
            case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      val expiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filter {
            case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      (
        existingDelegatedStakes,
        unexpiredWithdrawals,
        expiredWithdrawals
      )
    }

    private def acceptNodeCollaterals(lastSnapshotContext: GlobalSnapshotInfo, epochProgress: EpochProgress)(implicit h: Hasher[F]): (
      SortedMap[Address, SortedSet[NodeCollateralRecord]],
      SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
      SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]
    ) = {
      val existingNodeCollaterals =
        lastSnapshotContext.activeNodeCollaterals.getOrElse(SortedMap.empty[Address, SortedSet[NodeCollateralRecord]])
      val existingWithdrawals =
        lastSnapshotContext.nodeCollateralWithdrawals.getOrElse(SortedMap.empty[Address, SortedSet[PendingNodeCollateralWithdrawal]])

      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      val unexpiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filterNot {
            case PendingNodeCollateralWithdrawal(_, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      val expiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filter {
            case PendingNodeCollateralWithdrawal(_, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }
      (existingNodeCollaterals, unexpiredWithdrawals, expiredWithdrawals)
    }

    private def acceptAllowSpends(
      epochProgress: EpochProgress,
      activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allAcceptedSpendTxns: List[SpendTransaction]
    )(implicit hasher: Hasher[F]): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
      val allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val unexpiredGlobalAllowSpends = (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(lastActiveGlobalAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      val unexpiredGlobalWithoutSpendTransactions =
        unexpiredGlobalAllowSpends.toList.foldLeftM(unexpiredGlobalAllowSpends) {
          case (acc, (address, allowSpends)) =>
            allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
              val validAllowSpends = hashedAllowSpends
                .filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
                .map(_.signed)
                .to(SortedSet)

              acc + (address -> validAllowSpends)
            }
        }

      def processMetagraphAllowSpends(
        metagraphId: Address,
        metagraphAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
        accAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
        val lastActiveMetagraphAllowSpends =
          accAllowSpends.getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

        metagraphAllowSpends.toList.traverse {
          case (address, addressAllowSpends) =>
            val lastAddressAllowSpends = lastActiveMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])

            val unexpired = (lastAddressAllowSpends ++ addressAllowSpends)
              .filter(_.lastValidEpochProgress >= epochProgress)

            val unexpiredWithoutSpendTransactions = unexpired.toList
              .traverse(_.toHashed)
              .map { hashedAllowSpends =>
                hashedAllowSpends.filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
              }
              .map(_.map(_.signed).toSortedSet)

            unexpiredWithoutSpendTransactions.map(validAllowSpends => address -> validAllowSpends)
        }.map { updatedMetagraphAllowSpends =>
          accAllowSpends + (metagraphId.some -> SortedMap(updatedMetagraphAllowSpends: _*))
        }
      }

      activeAllowSpendsFromCurrencySnapshots.toList
        .foldLeft(lastActiveAllowSpends.pure[F]) {
          case (accAllowSpendsF, (metagraphId, metagraphAllowSpends)) =>
            for {
              accAllowSpends <- accAllowSpendsF
              updatedAllowSpends <- processMetagraphAllowSpends(metagraphId, metagraphAllowSpends, accAllowSpends)
            } yield updatedAllowSpends
        }
        .flatMap { updatedCurrencyAllowSpends =>
          unexpiredGlobalWithoutSpendTransactions.map { validGlobalAllowSpends =>
            if (validGlobalAllowSpends.nonEmpty)
              updatedCurrencyAllowSpends + (None -> validGlobalAllowSpends)
            else
              updatedCurrencyAllowSpends
          }
        }
    }

    private def acceptTokenLocks(
      epochProgress: EpochProgress,
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[TokenLock]]]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).toList
        .foldM(lastActiveGlobalTokenLocks) {
          case (acc, (address, tokenLocks)) =>
            val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
            val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch.forall(_ >= epochProgress))
            val addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
            val unlocksRefs = addressTokenUnlocks.map(_.tokenLockRef)

            unexpired
              .foldM(SortedSet.empty[Signed[TokenLock]]) { (acc, tokenLock) =>
                tokenLock.toHashed.map { tlh =>
                  if (unlocksRefs.contains(tlh.hash)) acc
                  else acc + tokenLock
                }
              }
              .map { updatedLocks =>
                acc.updated(address, updatedLocks)
              }
        }
        .map(updateTokenLocks => updateTokenLocks.filterNot(_._2.isEmpty))
    }

    private def updateTokenLockBalances(
      currencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
      maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): SortedMap[Address, SortedMap[Address, Balance]] = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      currencySnapshots.foldLeft(lastTokenLockBalances) {
        case (accTokenLockBalances, (metagraphId, currencySnapshotWithState)) =>
          val activeTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] =
            currencySnapshotWithState.collect {
              case Right((_, info)) => info.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
            }.foldLeft(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]) { (acc, curr) =>
              curr.foldLeft(acc) {
                case (accMap, (address, locks)) =>
                  val mergedLocks = accMap.getOrElse(address, SortedSet.empty[Signed[TokenLock]]) ++ locks
                  accMap.updated(address, mergedLocks)
              }
            }

          val metagraphTokenLocksAmounts = activeTokenLocks.foldLeft(SortedMap.empty[Address, Balance]) {
            case (accTokenLockBalances, addressTokenLocks) =>
              val (address, tokenLocks) = addressTokenLocks
              val amount = NonNegLong.unsafeFrom(tokenLocks.toList.map(_.amount.value.value).sum)
              accTokenLockBalances.updated(address, Balance(amount))
          }

          accTokenLockBalances + (metagraphId -> metagraphTokenLocksAmounts)
      }
    }

    private def filterExpiredAllowSpends(
      allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
      allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap)

    private def filterExpiredTokenLocks(
      tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
      tokenLocks.view.mapValues(_.filter(_.unlockEpoch.exists(_ < epochProgress))).to(SortedMap)

    private def updateGlobalBalancesByAllowSpends(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
        Right(currentBalances)
      ) {
        case (accEither, (address, allowSpends)) =>
          for {
            acc <- accEither
            initialBalance = acc.getOrElse(address, Balance.empty)

            unexpiredBalance <- {
              val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)

              unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                  balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                } yield balanceAfterFee
              }
            }

            expiredBalance <- {
              val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)

              expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                } yield balanceAfterExpiredAmount
              }
            }

            updatedAcc = acc.updated(address, expiredBalance)
          } yield updatedAcc
      }
    }

    private def updateGlobalBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      globalSpendTransactions: List[SpendTransaction]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] =
      globalSpendTransactions.foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
        (innerAccEither, spendTransaction) =>
          for {
            innerAcc <- innerAccEither
            destinationAddress = spendTransaction.destination
            sourceAddress = spendTransaction.source

            addressAllowSpends = allGlobalAllowSpends.getOrElse(sourceAddress, List.empty)
            spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
            currentDestinationBalance = innerAcc.getOrElse(destinationAddress, Balance.empty)

            updatedBalances <- spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
              addressAllowSpends.find(_.hash === allowSpendRef)
            } match {
              case Some(allowSpend) =>
                val sourceAllowSpendAddress = allowSpend.source
                val currentSourceBalance = innerAcc.getOrElse(sourceAllowSpendAddress, Balance.empty)
                val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.plus(
                    Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue))
                  )
                } yield
                  innerAcc
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAllowSpendAddress, updatedSourceBalance)

              case None =>
                val currentSourceBalance = innerAcc.getOrElse(sourceAddress, Balance.empty)

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.minus(spendTransactionAmount)
                } yield
                  innerAcc
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAddress, updatedSourceBalance)
            }
          } yield updatedBalances
      }

    private def updateGlobalBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      generatedTokenUnlocksByAddress: Map[Address, List[TokenUnlock]]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
        Right(currentBalances)
      ) {
        case (accEither, (address, tokenLocks)) =>
          for {
            acc <- accEither
            initialBalance = acc.getOrElse(address, Balance.empty)

            unexpiredBalance <- {
              val unexpired = tokenLocks.filter(_.unlockEpoch.forall(_ >= epochProgress))

              unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, tokenLock) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterAmount <- currentBalance.minus(TokenLockAmount.toAmount(tokenLock.amount))
                  balanceAfterFee <- balanceAfterAmount.minus(TokenLockFee.toAmount(tokenLock.fee))
                } yield balanceAfterFee
              }
            }

            expiredBalance <- {
              val expired = tokenLocks.filter(_.unlockEpoch.exists(_ < epochProgress))

              expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterExpiredAmount <- currentBalance.plus(TokenLockAmount.toAmount(allowSpend.amount))
                } yield balanceAfterExpiredAmount
              }
            }
            addressTokenUnlocks = generatedTokenUnlocksByAddress.getOrElse(address, List.empty)
            finalBalance <-
              addressTokenUnlocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(expiredBalance)) {
                case (currentBalanceEither, tokenUnlock) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterUnlock <- currentBalance.plus(TokenLockAmount.toAmount(tokenUnlock.amount))
                  } yield balanceAfterUnlock
              }

            updatedAcc = acc.updated(address, finalBalance)
          } yield updatedAcc
      }
    }

    private def acceptTransactionRefs(
      lastTxRefs: SortedMap[Address, TransactionReference],
      lastTxRefsContextUpdate: Map[Address, TransactionReference],
      acceptedTransactions: SortedSet[Signed[Transaction]]
    ): SortedMap[Address, TransactionReference] = {
      val updatedRefs = lastTxRefs ++ lastTxRefsContextUpdate
      val newDestinationAddresses = acceptedTransactions.map(_.destination) -- updatedRefs.keySet
      updatedRefs ++ newDestinationAddresses.toList.map(_ -> TransactionReference.empty)
    }

    private def acceptAllowSpendRefs(
      lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
      lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
    ): SortedMap[Address, AllowSpendReference] =
      lastAllowSpendRefs ++ lastAllowSpendContextUpdate

    private def acceptTokenLockRefs(
      lastTokenLockRefs: SortedMap[Address, TokenLockReference],
      lastTokenLockContextUpdate: Map[Address, TokenLockReference]
    ): SortedMap[Address, TokenLockReference] =
      lastTokenLockRefs ++ lastTokenLockContextUpdate

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[Block]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      ordinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral,
        TransactionReference.empty
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, ordinal)
    }

    private def acceptAllowSpendBlocks(
      blocksForAcceptance: List[Signed[AllowSpendBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]) = {
      val context = AllowSpendBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastAllowSpendRefs.getOrElse(Map.empty),
        collateral,
        AllowSpendReference.empty
      )

      allowSpendBlockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal)
    }

    private def acceptTokenLockBlocks(
      blocksForAcceptance: List[Signed[TokenLockBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]) = {
      val context = TokenLockBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTokenLockRefs.getOrElse(Map.empty),
        collateral,
        TokenLockReference.empty
      )

      tokenLockBlockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
      }

    private def acceptMetagraphSyncData(
      lastSnapshotContext: GlobalSnapshotInfo,
      incomingCurrencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
      acceptedSpendActions: Map[Address, List[SpendAction]],
      currentGlobalOrdinal: SnapshotOrdinal,
      currentGlobalEpochProgress: EpochProgress
    ): SortedMap[Address, MetagraphSyncDataInfo] =
      lastSnapshotContext.metagraphSyncData.map { existingData =>
        val updatedFromSnapshots = updateFromCurrencySnapshots(
          existingData,
          incomingCurrencySnapshots,
          currentGlobalOrdinal,
          currentGlobalEpochProgress
        )

        val updatedFromSpendActions = updateFromSpendActions(
          updatedFromSnapshots,
          acceptedSpendActions,
          currentGlobalOrdinal
        )

        existingData ++ updatedFromSpendActions
      }
        .getOrElse(SortedMap.empty[Address, MetagraphSyncDataInfo])

    private def updateFromCurrencySnapshots(
      existingData: SortedMap[Address, MetagraphSyncDataInfo],
      currencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
      currentOrdinal: SnapshotOrdinal,
      currentEpochProgress: EpochProgress
    ): SortedMap[Address, MetagraphSyncDataInfo] =
      currencySnapshots.map {
        case (address, snapshots) =>
          val currentInfo = existingData.getOrElse(address, MetagraphSyncDataInfo.empty)
          val lastSyncOrdinal = extractLastSynchronizedOrdinal(snapshots)
          val lastSyncOrdinalWithOffset = lastSyncOrdinal.plus(metagraphsSyncConfig.offsetToCleanUnappliedOrdinals)

          val updatedInfo = currentInfo
            .focus(_.globalOrdinalLastAcceptedOn)
            .replace(currentOrdinal)
            .focus(_.globalEpochProgressLastAcceptedOn)
            .replace(currentEpochProgress)
            .focus(_.unappliedGlobalChangeOrdinals)
            .modify(_.filter(_ >= lastSyncOrdinalWithOffset))

          address -> updatedInfo
      }.toSortedMap

    private def updateFromSpendActions(
      currentData: SortedMap[Address, MetagraphSyncDataInfo],
      spendActions: Map[Address, List[SpendAction]],
      currentOrdinal: SnapshotOrdinal
    ): SortedMap[Address, MetagraphSyncDataInfo] = {
      val currencySpendTransactions = extractCurrencySpendTransactions(spendActions)

      currencySpendTransactions.foldLeft(currentData) { (acc, transaction) =>
        val metagraphId = transaction.currencyId.get.value
        val currentInfo = acc.getOrElse(metagraphId, MetagraphSyncDataInfo.empty)

        val updatedInfo = currentInfo
          .focus(_.unappliedGlobalChangeOrdinals)
          .modify(trimUnappliedOrdinals(_, currentOrdinal))

        acc.updated(metagraphId, updatedInfo)
      }
    }

    private def extractLastSynchronizedOrdinal(snapshots: List[CurrencySnapshotWithState]): SnapshotOrdinal =
      snapshots.flatMap {
        case Left(left)           => left.globalSyncView.map(_.ordinal)
        case Right((snapshot, _)) => snapshot.globalSyncView.map(_.ordinal)
      }.foldLeft(SnapshotOrdinal.MinValue)(_ max _)

    private def extractCurrencySpendTransactions(spendActions: Map[Address, List[SpendAction]]) =
      spendActions.values.flatten
        .flatMap(_.spendTransactions.toList)
        .filter(_.currencyId.isDefined)

    private def trimUnappliedOrdinals(
      currentOrdinals: SortedSet[SnapshotOrdinal],
      newOrdinal: SnapshotOrdinal
    ): SortedSet[SnapshotOrdinal] = {
      val maxSize = metagraphsSyncConfig.maxUnappliedGlobalChangeOrdinals.value
      val updated = currentOrdinals + newOrdinal

      if (updated.size <= maxSize) updated
      else updated.dropRight(updated.size - maxSize)
    }

    def emitAllowSpendsExpired(
      addressToSet: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      addressToSet.values.flatten.toList
        .traverse(_.toHashed)
        .map(_.map(hashed => AllowSpendExpiration(hashed.hash): SharedArtifact).toSortedSet)

    def emitTokenUnlocks(
      expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      expiredTokenLocks.values.flatten.toList
        .traverse(_.toHashed)
        .map { hashedLocks =>
          val newUnlocks = hashedLocks.collect {
            case hashed =>
              TokenUnlock(
                hashed.hash,
                hashed.amount,
                hashed.currencyId,
                hashed.source
              )
          }

          SortedSet.from[SharedArtifact](newUnlocks)
        }

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }
  }
}
