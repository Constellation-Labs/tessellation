package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshotV1, CurrencySnapshotInfoV1}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.merkletree.syntax._
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
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.SpendActionValidationError
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
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeReference, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralReference, UpdateNodeCollateral}
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._
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
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    validationType: StateChannelValidationType,
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
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
      SortedSet[SharedArtifact]
    )
  ]
}

object GlobalSnapshotAcceptanceManager {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: HasherSelector: SecurityProvider](
    tokenLocksAddedToGl0Ordinal: SnapshotOrdinal,
    delegatedStakingAddedToGl0Ordinal: SnapshotOrdinal,
    nodeCollateralsAddedToGl0Ordinal: SnapshotOrdinal,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    updateNodeParametersAcceptanceManager: UpdateNodeParametersAcceptanceManager[F],
    updateDelegatedStakeAcceptanceManager: UpdateDelegatedStakeAcceptanceManager[F],
    updateNodeCollateralAcceptanceManager: UpdateNodeCollateralAcceptanceManager[F],
    spendActionValidator: SpendActionValidator[F],
    collateral: Amount,
    withdrawalTimeLimit: NonNegLong
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
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      validationType: StateChannelValidationType,
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
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
        SortedSet[SharedArtifact]
      )
    ] = {
      implicit val hasher = HasherSelector[F].getForOrdinal(ordinal)

      for {
        acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips, ordinal)

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
              lastGlobalSnapshots,
              getGlobalSnapshotByOrdinal
            )

        acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

        transactionsRefs = acceptTransactionRefs(
          lastSnapshotContext.lastTxRefs,
          acceptanceResult.contextUpdate.lastTxRefs,
          acceptedTransactions
        )

        rewards <- calculateRewardsFn(acceptedTransactions)

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
          updatedGlobalBalances ++ currencyAcceptanceBalanceUpdate,
          rewards
        )

        spendActions = incomingCurrencySnapshots.toList.map {
          case (_, Left(_))             => Map.empty[Address, List[SharedArtifact]]
          case (address, Right((s, _))) => Map(address -> (s.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).toList))
        }
          .foldLeft(Map.empty[Address, List[SharedArtifact]])(_ |+| _)
          .view
          .mapValues(_.collect { case sa: SpendAction => sa })
          .filter { case (_, actions) => actions.nonEmpty }
          .toMap

        currencyBalances = incomingCurrencySnapshots.toList.map {
          case (_, Left(_))              => Map.empty[Option[Address], SortedMap[Address, Balance]]
          case (address, Right((_, si))) => Map(address.some -> si.balances)
        }
          .foldLeft(Map.empty[Option[Address], SortedMap[Address, Balance]])(_ ++ _)
        globalBalances = Map(none[Address] -> updatedBalancesByRewards)

        lastActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )

        spendTransactionsValidations <- spendActions.toList.traverse {
          case (address, actions) =>
            actions.traverse { action =>
              spendActionValidator
                .validate(
                  action,
                  lastActiveAllowSpends,
                  currencyBalances ++ globalBalances,
                  address
                )
                .map {
                  case Valid(validAction) => Right(validAction)
                  case Invalid(errors)    => Left((action, errors.toNonEmptyList.toList))
                }
            }.map(address -> _.partitionMap(identity))
        }

        acceptedSpendActions = spendTransactionsValidations.map {
          case (address, (_, accepted)) => address -> accepted
        }.filter {
          case (_, spendAction) => spendAction.nonEmpty
        }.toMap

        rejectedSpendActions = spendTransactionsValidations.flatMap {
          case (address, (rejected, _)) =>
            rejected.map {
              case (action: SpendAction, errors: List[SpendActionValidationError]) =>
                address -> (action, errors)
            }
        }

        _ <- Slf4jLogger.getLogger[F].debug(s"--- Accepted spend actions: ${acceptedSpendActions.show}")
        _ <- Slf4jLogger.getLogger[F].debug(s"--- Rejected spend actions: ${rejectedSpendActions.show}")

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

        activeAllowSpendsFromCurrencySnapshots = incomingCurrencySnapshots.map { case (key, value) => (key, value) }
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

        updatedBalancesByAllowSpends = updateGlobalBalancesByAllowSpends(
          epochProgress,
          updatedBalancesByRewards,
          globalAllowSpends,
          globalActiveAllowSpends
        )

        updatedGlobalTokenLocks =
          if (ordinal < tokenLocksAddedToGl0Ordinal) {
            None
          } else {
            acceptTokenLocks(
              epochProgress,
              globalTokenLocks,
              globalActiveTokenLocks
            ).some
          }

        updatedTokenLockRefs =
          if (ordinal < tokenLocksAddedToGl0Ordinal) {
            None
          } else {
            acceptTokenLockRefs(
              globalLastTokenLockRefs,
              tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs
            ).some
          }

        updatedTokenLockBalances = updateTokenLockBalances(
          incomingCurrencySnapshots,
          lastSnapshotContext.tokenLockBalances
        )

        updatedBalancesByTokenLocks = updateGlobalBalancesByTokenLocks(
          epochProgress,
          updatedBalancesByAllowSpends,
          globalTokenLocks,
          globalActiveTokenLocks
        )

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
        )

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

        acceptedUpdateNodeParameters <- updateNodeParametersAcceptanceManager
          .acceptUpdateNodeParameters(unpEvents, lastSnapshotContext)
          .map(acceptanceResult =>
            acceptanceResult.accepted.flatMap(signed => signed.proofs.toList.map(proof => (proof.id, signed))).toSortedMap
          )

        lastSnapshotUpdateNodeParameters = lastSnapshotContext.updateNodeParameters.getOrElse(
          SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]
        )

        updatedUpdateNodeParameters = lastSnapshotUpdateNodeParameters ++ acceptedUpdateNodeParameters.view.mapValues(unp => (unp, ordinal))

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

        (createDelegatedStakes, withdrawDelegatedStakes) <- withdrawDelegatedStakes(lastSnapshotContext, epochProgress)
        updatedCreateDelegatedStakes =
          if (ordinal < delegatedStakingAddedToGl0Ordinal) None
          else (createDelegatedStakes ++ delegatedStakeAcceptanceResult.acceptedCreates).some
        updatedWithdrawDelegatedStakes =
          if (ordinal < delegatedStakingAddedToGl0Ordinal) None
          else (withdrawDelegatedStakes ++ delegatedStakeAcceptanceResult.acceptedWithdrawals).some

        (createNodeCollaterals, withdrawNodeCollaterals) <- withdrawNodeCollaterals(lastSnapshotContext, epochProgress)
        updatedCreateNodeCollaterals =
          if (ordinal < nodeCollateralsAddedToGl0Ordinal) None
          else (createNodeCollaterals ++ nodeCollateralAcceptanceResult.acceptedCreates).some
        updatedWithdrawNodeCollaterals =
          if (ordinal < nodeCollateralsAddedToGl0Ordinal) None
          else (withdrawNodeCollaterals ++ nodeCollateralAcceptanceResult.acceptedWithdrawals).some

        gsi = GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          transactionsRefs,
          updatedBalancesBySpendTransactions,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs,
          updatedAllowSpends.some,
          updatedGlobalTokenLocks,
          updatedTokenLockBalances.some,
          updatedAllowSpendRefs.some,
          updatedTokenLockRefs,
          updatedUpdateNodeParameters.some,
          updatedCreateDelegatedStakes,
          updatedWithdrawDelegatedStakes,
          updatedCreateNodeCollaterals,
          updatedWithdrawNodeCollaterals
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
          allowSpendsExpiredEvents ++ tokenUnlocksEvents
        )
    }

    private def withdrawDelegatedStakes(lastSnapshotContext: GlobalSnapshotInfo, epochProgress: EpochProgress)(implicit h: Hasher[F]): F[
      (
        SortedMap[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]],
        SortedMap[Address, List[(Signed[UpdateDelegatedStake.Withdraw], EpochProgress)]]
      )
    ] = {
      val lastCreateDelegatedStakes = lastSnapshotContext.activeDelegatedStakes.getOrElse(
        SortedMap.empty[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]]
      )

      val lastWithdrawDelegatedStakes = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
        SortedMap.empty[Address, List[(Signed[UpdateDelegatedStake.Withdraw], EpochProgress)]]
      )
      val withdrawalEpoch = EpochProgress(withdrawalTimeLimit)
      val currentWithdrawals =
        lastWithdrawDelegatedStakes.view
          .mapValues(_.filter { case (_, epoch) => (epoch |+| withdrawalEpoch) <= epochProgress })
          .filter(x => x._2.nonEmpty)
      val references = currentWithdrawals.mapValues(_.map(_._1.stakeRef)).values.flatten.toSet

      for {
        stakesWithReferences <- lastCreateDelegatedStakes.toList.traverse {
          case (address, list) =>
            list.traverse { case x @ (signed, ord) => DelegatedStakeReference.of(signed).map(ref => (x, ref)) }.map(x => (address, x))
        }
        filteredCreates = stakesWithReferences.map {
          case (addr, list) => (addr, list.filterNot { case (_, ref) => references(ref.hash) }.map(_._1))
        }.toSortedMap
        filteredWithdawals = lastWithdrawDelegatedStakes.map {
          case (addr, list) => (addr, list.filterNot { case (signed, _) => references(signed.stakeRef) })
        }
      } yield (filteredCreates.filter(_._2.nonEmpty), filteredWithdawals.filter(_._2.nonEmpty))
    }

    private def withdrawNodeCollaterals(lastSnapshotContext: GlobalSnapshotInfo, epochProgress: EpochProgress)(implicit h: Hasher[F]): F[
      (
        SortedMap[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]],
        SortedMap[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]]
      )
    ] = {
      val lastCreates = lastSnapshotContext.activeNodeCollaterals.getOrElse(
        SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]]
      )

      val lastWithdraws = lastSnapshotContext.nodeCollateralWithdrawals.getOrElse(
        SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]]
      )
      val withdrawalEpoch = epochProgress |+| EpochProgress(withdrawalTimeLimit)
      val currentWithdrawals =
        lastWithdraws.view
          .mapValues(_.filter { case (_, epoch) => (epoch |+| withdrawalEpoch) <= epochProgress })
          .filter(x => x._2.nonEmpty)

      val references = currentWithdrawals.mapValues(_.map(_._1.collateralRef)).values.flatten.toSet

      for {
        stakesWithReferences <- lastCreates.toList.traverse {
          case (address, list) =>
            list.traverse { case x @ (signed, ord) => NodeCollateralReference.of(signed).map(ref => (x, ref)) }.map(x => (address, x))
        }
        filteredCreates = stakesWithReferences.map {
          case (addr, list) => (addr, list.filterNot { case (_, ref) => references(ref.hash) }.map(_._1))
        }.toSortedMap
        filteredWithdawals = lastWithdraws.map {
          case (addr, list) => (addr, list.filterNot { case (signed, _) => references(signed.collateralRef) })
        }
      } yield (filteredCreates.filter(_._2.nonEmpty), filteredWithdawals.filter(_._2.nonEmpty))
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
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).foldLeft(lastActiveGlobalTokenLocks) {
        case (acc, (address, tokenLocks)) =>
          val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
          val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch.forall(_ >= epochProgress))
          acc + (address -> unexpired)
      }
    }

    private def updateTokenLockBalances(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState],
      maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): SortedMap[Address, SortedMap[Address, Balance]] = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      currencySnapshots.foldLeft(lastTokenLockBalances) {
        case (accTokenLockBalances, (metagraphId, currencySnapshotWithState)) =>
          val activeTokenLocks = currencySnapshotWithState match {
            case Left(_)          => SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
            case Right((_, info)) => info.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
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
    ): SortedMap[Address, Balance] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(currentBalances) {
        case (acc, (address, allowSpends)) =>
          val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)
          val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)

          val updatedBalanceUnexpired =
            unexpired.foldLeft(acc.getOrElse(address, Balance.empty)) { (currentBalance, allowSpend) =>
              currentBalance
                .minus(SwapAmount.toAmount(allowSpend.amount))
                .getOrElse(currentBalance)
                .minus(AllowSpendFee.toAmount(allowSpend.fee))
                .getOrElse(currentBalance)
            }
          val updatedBalanceExpired = expired.foldLeft(updatedBalanceUnexpired) { (currentBalance, allowSpend) =>
            currentBalance
              .plus(SwapAmount.toAmount(allowSpend.amount))
              .getOrElse(currentBalance)
          }

          acc.updated(address, updatedBalanceExpired)
      }
    }

    private def updateGlobalBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      globalSpendTransactions: List[SpendTransaction]
    ): SortedMap[Address, Balance] =
      globalSpendTransactions.foldLeft(currentBalances) { (innerAcc, spendTransaction) =>
        val destinationAddress = spendTransaction.destination
        val sourceAddress = spendTransaction.source

        val addressAllowSpends = allGlobalAllowSpends.getOrElse(sourceAddress, List.empty)
        val spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
        val currentDestinationBalance = innerAcc.getOrElse(destinationAddress, Balance.empty)

        spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
          addressAllowSpends.find(_.hash === allowSpendRef)
        } match {
          case Some(allowSpend) =>
            val sourceAddress = allowSpend.source
            val currentSourceBalance = innerAcc.getOrElse(sourceAddress, Balance.empty)
            val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value

            val updatedDestinationBalance = currentDestinationBalance
              .plus(spendTransactionAmount)
              .getOrElse(currentDestinationBalance)

            val updatedSourceBalance = currentSourceBalance
              .plus(Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue)))
              .getOrElse(currentSourceBalance)

            innerAcc
              .updated(destinationAddress, updatedDestinationBalance)
              .updated(sourceAddress, updatedSourceBalance)

          case None =>
            val currentSourceBalance = innerAcc.getOrElse(sourceAddress, Balance.empty)

            val updatedDestinationBalance = currentDestinationBalance
              .plus(spendTransactionAmount)
              .getOrElse(currentDestinationBalance)

            val updatedSourceBalance = currentSourceBalance
              .minus(spendTransactionAmount)
              .getOrElse(currentSourceBalance)

            innerAcc
              .updated(destinationAddress, updatedDestinationBalance)
              .updated(sourceAddress, updatedSourceBalance)
        }
      }

    private def updateGlobalBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    ): SortedMap[Address, Balance] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).foldLeft(currentBalances) {
        case (acc, (address, tokenLocks)) =>
          val unexpired = tokenLocks.filter(_.unlockEpoch.forall(_ >= epochProgress))
          val expired = tokenLocks.filter(_.unlockEpoch.exists(_ < epochProgress))

          val updatedBalanceUnexpired =
            unexpired.foldLeft(acc.getOrElse(address, Balance.empty)) { (currentBalance, tokenLock) =>
              currentBalance
                .minus(TokenLockAmount.toAmount(tokenLock.amount))
                .getOrElse(currentBalance)
                .minus(TokenLockFee.toAmount(tokenLock.fee))
                .getOrElse(currentBalance)
            }
          val updatedBalanceExpired = expired.foldLeft(updatedBalanceUnexpired) { (currentBalance, allowSpend) =>
            currentBalance
              .plus(TokenLockAmount.toAmount(allowSpend.amount))
              .getOrElse(currentBalance)
          }

          acc.updated(address, updatedBalanceExpired)
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
