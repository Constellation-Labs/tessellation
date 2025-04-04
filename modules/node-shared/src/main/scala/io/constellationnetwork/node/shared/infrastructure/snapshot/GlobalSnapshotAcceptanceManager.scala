package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

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
import io.constellationnetwork.schema.balance.Balance._
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralReference, UpdateNodeCollateral}
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
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
    delegatedRewardsDistributor: DelegatedRewardsDistributor[F],
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

  def make[F[_]: Async: Parallel: HasherSelector: SecurityProvider](
    tokenLocksAddedToGl0Ordinal: SnapshotOrdinal,
    delegatedStakingAddedToGl0Ordinal: SnapshotOrdinal,
    delegatedStakingRewardsAddedToGl0Ordinal: SnapshotOrdinal,
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
      delegatedRewardsDistributor: DelegatedRewardsDistributor[F],
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
        nodesInConsensus = SortedSet.from(acceptedTransactions.map(_.proofs.head.id))
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

        transactionsRefs = acceptTransactionRefs(
          lastSnapshotContext.lastTxRefs,
          acceptanceResult.contextUpdate.lastTxRefs,
          acceptedTransactions
        )

        // Calculate total rewards to mint for this snapshot
        totalEmittedRewardsAmount <- delegatedRewardsDistributor.calculateTotalRewardsToMint(epochProgress)

        (
          unexpiredCreateDelegatedStakes,
          expiredCreateDelegatedStakes,
          unexpiredWithdrawalsDelegatedStaking,
          expiredWithdrawalsDelegatedStaking
        ) <- acceptDelegatedStakes(lastSnapshotContext, epochProgress)

        // Calculate delegator rewards for all active and newly accepted delegated stakes
        delegatorRewardsMap <-
          if (ordinal < delegatedStakingAddedToGl0Ordinal) Map.empty[Address, Map[Id, Amount]].pure[F]
          else {
            val allActiveDelegatorRecordsBeforeRewards =
              unexpiredCreateDelegatedStakes |+| delegatedStakeAcceptanceResult.acceptedCreates.map {
                case (addr, st) =>
                  addr -> st.map { case (ev, ord) => DelegatedStakeRecord(ev, ord, Balance.empty, Amount(NonNegLong.unsafeFrom(0L))) }
              }

            delegatedRewardsDistributor
              .calculateDelegatorRewards(
                allActiveDelegatorRecordsBeforeRewards,
                updatedUpdateNodeParameters,
                epochProgress,
                totalEmittedRewardsAmount
              )
          }

        // Apply the calculated rewards to delegated stake records
        updatedCreateDelegatedStakes <-
          if (ordinal < delegatedStakingAddedToGl0Ordinal) SortedMap.empty[Address, List[DelegatedStakeRecord]].pure[F]
          else {
            val allActiveDelegatorRecordsBeforeRewards =
              unexpiredCreateDelegatedStakes |+| delegatedStakeAcceptanceResult.acceptedCreates.map {
                case (addr, st) =>
                  addr -> st.map { case (ev, ord) => DelegatedStakeRecord(ev, ord, Balance.empty, Amount(NonNegLong.unsafeFrom(0L))) }
              }

            allActiveDelegatorRecordsBeforeRewards.map {
              case (addr, recs) =>
                addr -> recs.map {
                  case DelegatedStakeRecord(event, ord, bal, _) =>
                    val nodeSpecificReward = delegatorRewardsMap
                      .get(addr)
                      .flatMap(_.get(event.value.nodeId.toId))
                      .getOrElse(Amount.empty)

                    val disbursedBalance = bal.plus(nodeSpecificReward).toOption.getOrElse(Balance.empty)

                    DelegatedStakeRecord(event, ord, disbursedBalance, nodeSpecificReward)
                }
            }.pure[F]
          }

        // pull reward balance from last snapshot active delegation for each accepted withdrawal
        updatedWithdrawDelegatedStakes <-
          if (ordinal < delegatedStakingAddedToGl0Ordinal) SortedMap.empty[Address, List[PendingWithdrawal]].pure[F]
          else
            delegatedStakeAcceptanceResult.acceptedWithdrawals.toList.traverse {
              case (addr, acceptedWithdrawls) =>
                acceptedWithdrawls.traverse {
                  case (ev, ep) =>
                    lastSnapshotContext.activeDelegatedStakes
                      .flatTraverse(_.get(addr).flatTraverse {
                        _.findM { s =>
                          DelegatedStakeReference.of(s.event).map(_.hash === ev.stakeRef)
                        }.map(_.map(rec => PendingWithdrawal(ev, rec.rewards, ep)))
                      })
                      .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing user delegations")))
                }.map(addr -> _)
            }.map(_.toSortedMap).map(unexpiredWithdrawalsDelegatedStaking |+| _)

        // Calculate node operator rewards based on delegator rewards, this includes both static and dynamic node rewards
        nodeOperatorRewards <-
          if (ordinal < delegatedStakingAddedToGl0Ordinal) SortedSet.empty[RewardTransaction].pure[F]
          else
            delegatedRewardsDistributor.calculateNodeOperatorRewards(
              delegatorRewardsMap,
              updatedUpdateNodeParameters,
              nodesInConsensus,
              epochProgress,
              totalEmittedRewardsAmount
            )

        // Generate reward transactions for expiring withdrawals
        withdrawalRewardTxs <-
          if (ordinal < delegatedStakingRewardsAddedToGl0Ordinal) SortedSet.empty[RewardTransaction].pure[F]
          else
            delegatedRewardsDistributor.calculateWithdrawalRewardTransactions(
              expiredWithdrawalsDelegatedStaking.toList.flatMap {
                case (address, withdrawals) =>
                  withdrawals.mapFilter { withdrawal =>
                    Option.when(withdrawal.rewards.value > Balance.empty.value) {
                      (address, Amount(NonNegLong.unsafeFrom(withdrawal.rewards.value.value)))
                    }
                  }
              }.toMap
            )

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
          updatedGlobalBalances ++ currencyAcceptanceBalanceUpdate,
          withdrawalRewardTxs ++ nodeOperatorRewards
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

        currencyBalances = currencySnapshots.toList.map {
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

        (unexpiredCreateNodeCollaterals, _, unexpiredWithdrawNodeCollaterals, _) <- acceptNodeCollaterals(
          lastSnapshotContext,
          epochProgress
        )
        updatedCreateNodeCollaterals =
          if (ordinal < nodeCollateralsAddedToGl0Ordinal) None
          else (unexpiredCreateNodeCollaterals |+| nodeCollateralAcceptanceResult.acceptedCreates).some
        updatedWithdrawNodeCollaterals =
          if (ordinal < nodeCollateralsAddedToGl0Ordinal) None
          else (unexpiredWithdrawNodeCollaterals |+| nodeCollateralAcceptanceResult.acceptedWithdrawals).some

        expiredCreateDelegatedStakesByRef <- expiredCreateDelegatedStakes.values.flatten.toList.traverse {
          case DelegatedStakeRecord(value, _, _, _) =>
            value.toHashed.map(hashed => hashed.hash -> value)
        }.map(_.toMap)

        generatedTokenUnlocks = generateTokenUnlocks(
          expiredWithdrawalsDelegatedStaking,
          expiredCreateDelegatedStakesByRef,
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

        gsi = GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          transactionsRefs,
          updatedBalancesBySpendTransactions,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs,
          updatedAllowSpends.some,
          if (ordinal < tokenLocksAddedToGl0Ordinal) none else updatedGlobalTokenLocks.some,
          updatedTokenLockBalances.some,
          updatedAllowSpendRefs.some,
          updatedTokenLockRefs,
          updatedUpdateNodeParameters.some,
          if (ordinal < delegatedStakingAddedToGl0Ordinal) none else updatedCreateDelegatedStakes.some,
          if (ordinal < delegatedStakingAddedToGl0Ordinal) none else updatedWithdrawDelegatedStakes.some,
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

    private def generateTokenUnlocks(
      expiredWithdrawalsDelegatedStaking: SortedMap[Address, List[PendingWithdrawal]],
      expiredCreatedDelegatesStakingByRef: Map[Hash, Signed[delegatedStake.UpdateDelegatedStake.Create]],
      globalActiveTokenLocksByRef: Map[Hash, Signed[TokenLock]]
    ): Either[DelegatedStakeError, Map[Address, List[TokenUnlock]]] =
      expiredWithdrawalsDelegatedStaking.toList.traverse {
        case (address, withdrawals) =>
          withdrawals.traverse {
            case PendingWithdrawal(withdraw, _, _) =>
              for {
                delegatedStaking <- expiredCreatedDelegatesStakingByRef
                  .get(withdraw.stakeRef)
                  .toRight(MissingDelegatedStaking(s"Missing delegated stake for stakeRef: ${withdraw.stakeRef}"))

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
    )(implicit h: Hasher[F]): F[
      (
        SortedMap[Address, List[DelegatedStakeRecord]],
        SortedMap[Address, List[DelegatedStakeRecord]],
        SortedMap[Address, List[PendingWithdrawal]],
        SortedMap[Address, List[PendingWithdrawal]]
      )
    ] = {
      val existingDelegatedStakes = lastSnapshotContext.activeDelegatedStakes.getOrElse(
        SortedMap.empty[Address, List[DelegatedStakeRecord]]
      )

      val existingWithdrawals = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
        SortedMap.empty[Address, List[PendingWithdrawal]]
      )

      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      def filterCreatesWithExpiredWithdrawals(
        address: Address
      ): F[Option[(Address, List[DelegatedStakeRecord])]] = {
        val addressCreates = existingDelegatedStakes.getOrElse(address, List.empty)
        val addressWithdrawals = existingWithdrawals.getOrElse(address, List.empty)

        addressCreates.traverse {
          case record @ DelegatedStakeRecord(createStake, _, _, _) =>
            DelegatedStakeReference.of(createStake).map { createRef =>
              val isExpired = addressWithdrawals.exists {
                case PendingWithdrawal(withdrawalStake, _, withdrawalEpoch) =>
                  withdrawalStake.stakeRef == createRef.hash && isWithdrawalExpired(withdrawalEpoch)
              }
              (record, isExpired)
            }
        }.map { processedCreates =>
          val unexpiredCreates = processedCreates.filterNot { case (_, isExpired) => isExpired }.map { case (r, _) => r }

          if (unexpiredCreates.isEmpty) None
          else Some(address -> unexpiredCreates)
        }
      }

      for {
        filteredResults <- existingDelegatedStakes.keys.toList
          .traverse(filterCreatesWithExpiredWithdrawals)
          .map(_.flatten)

        filteredUnexpired = SortedMap.empty[Address, List[DelegatedStakeRecord]] ++ filteredResults

        filteredExpired = SortedMap.empty[Address, List[DelegatedStakeRecord]] ++
          existingDelegatedStakes.keys.map { address =>
            val original = existingDelegatedStakes.getOrElse(address, List.empty)
            val unexpired = filteredUnexpired.getOrElse(address, List.empty)
            address -> original.diff(unexpired)
          }.filter(_._2.nonEmpty)

        unexpiredWithdrawals = existingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filterNot {
              case PendingWithdrawal(_, _, withdrawalEpoch) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        expiredWithdrawals = existingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filter {
              case PendingWithdrawal(_, _, withdrawalEpoch) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      } yield
        (
          filteredUnexpired,
          filteredExpired,
          unexpiredWithdrawals,
          expiredWithdrawals
        )
    }

    private def acceptNodeCollaterals(lastSnapshotContext: GlobalSnapshotInfo, epochProgress: EpochProgress)(implicit h: Hasher[F]): F[
      (
        SortedMap[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]],
        SortedMap[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]],
        SortedMap[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]],
        SortedMap[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]]
      )
    ] = {
      val existingCreates = lastSnapshotContext.activeNodeCollaterals.getOrElse(
        SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]]
      )
      val existingWithdrawals = lastSnapshotContext.nodeCollateralWithdrawals.getOrElse(
        SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]]
      )

      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      def processAddressCreate(address: Address): F[Option[(Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)])]] = {
        val addressNodeCollaterals = existingCreates.getOrElse(address, List.empty)

        addressNodeCollaterals.traverse {
          case nodeCollateralTuples @ (signed, _) =>
            NodeCollateralReference.of(signed).map { ref =>
              val isExpired = existingWithdrawals.exists {
                case (_, withdrawals) =>
                  withdrawals.exists {
                    case (withdrawal, withdrawalEpoch) =>
                      withdrawal.collateralRef == ref.hash && isWithdrawalExpired(withdrawalEpoch)
                  }
              }
              (nodeCollateralTuples, isExpired)
            }
        }.map { processedNodeCollaterals =>
          val unexpiredNodeCollaterals = processedNodeCollaterals.filterNot { case (_, isExpired) => isExpired }.map {
            case (tuple, _) => tuple
          }

          if (unexpiredNodeCollaterals.nonEmpty) {
            Some(address -> unexpiredNodeCollaterals)
          } else None
        }
      }

      for {
        filteredResults <- existingCreates.keys.toList
          .traverse(processAddressCreate)
          .map(_.flatten)

        filteredUnexpired = SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]] ++
          filteredResults

        filteredExpired = SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]] ++
          existingCreates.keys.map { address =>
            val original = existingCreates.getOrElse(address, List.empty)
            val unexpired = filteredUnexpired.getOrElse(address, List.empty)
            address -> (original.diff(unexpired))
          }.filter(_._2.nonEmpty)

        unexpiredWithdrawals = existingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filterNot {
              case (_, withdrawalEpoch) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        expiredWithdrawals = existingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filter {
              case (_, withdrawalEpoch) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }
      } yield (filteredUnexpired, filteredExpired, unexpiredWithdrawals, expiredWithdrawals)
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
        .map(updateTokenLocks => updateTokenLocks)
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
