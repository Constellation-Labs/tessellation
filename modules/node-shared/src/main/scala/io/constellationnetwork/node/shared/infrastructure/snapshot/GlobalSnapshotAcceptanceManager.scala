package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

import scala.collection.MapView
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
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
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

  private[snapshot] def generateDelegatedStakeTokenUnlocks(
    expiredWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    activeTokenLocksByRef: Map[Hash, Signed[TokenLock]],
    currentSnapshotOrdinal: SnapshotOrdinal,
    fixingDelegatedStakeDoubleWithdrawalOrdinal: SnapshotOrdinal
  ): Either[DelegatedStakeError, Map[Address, List[TokenUnlock]]] =
    if (currentSnapshotOrdinal < fixingDelegatedStakeDoubleWithdrawalOrdinal)
      // Preserve the historical grouping, ordering and duplicate behavior exactly below activation.
      expiredWithdrawals.toList.traverse {
        case (address, withdrawals) =>
          withdrawals.toList.traverse {
            case PendingDelegatedStakeWithdrawal(delegatedStaking, _, _, _) =>
              for {
                activeTokenLock <- activeTokenLocksByRef
                  .get(delegatedStaking.tokenLockRef)
                  .toRight(MissingTokenLock(s"Missing TokenLock for tokenLockRef: ${delegatedStaking.tokenLockRef}"))
              } yield
                TokenUnlock(
                  delegatedStaking.tokenLockRef,
                  activeTokenLock.amount,
                  activeTokenLock.currencyId,
                  activeTokenLock.source
                )
          }.map(address -> _)
      }.map(_.toMap)
    else
      // Defense in depth for duplicate pending state created before activation: a token lock's principal
      // can be credited only once, and the active lock—not the withdrawal map key—defines the owner.
      expiredWithdrawals.valuesIterator
        .flatMap(_.iterator.map(_.event.tokenLockRef))
        .toList
        .distinct
        .sorted
        .traverse { tokenLockRef =>
          activeTokenLocksByRef
            .get(tokenLockRef)
            .toRight(MissingTokenLock(s"Missing TokenLock for tokenLockRef: $tokenLockRef"))
            .map { activeTokenLock =>
              TokenUnlock(
                tokenLockRef,
                activeTokenLock.amount,
                activeTokenLock.currencyId,
                activeTokenLock.source
              )
            }
        }
        .map(tokenUnlocks => SortedMap.from(tokenUnlocks.groupBy(_.source)))

  private[snapshot] def excludeNaturallyExpiredDelegatedStakeUnlocks(
    generatedTokenUnlocks: Map[Address, List[TokenUnlock]],
    naturallyExpiredTokenLockRefs: Set[Hash],
    fixActive: Boolean
  ): Map[Address, List[TokenUnlock]] =
    if (!fixActive) generatedTokenUnlocks
    else
      generatedTokenUnlocks.map {
        case (address, unlocks) => address -> unlocks.filterNot(u => naturallyExpiredTokenLockRefs(u.tokenLockRef))
      }.filter { case (_, unlocks) => unlocks.nonEmpty }

  private[snapshot] case class DelegatedStakeTokenLockTransition(
    generatedTokenUnlocks: Map[Address, List[TokenUnlock]],
    activeTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    balances: SortedMap[Address, Balance],
    pendingWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    naturallyExpiredArtifacts: SortedSet[SharedArtifact],
    generatedArtifacts: SortedSet[SharedArtifact]
  )

  /** Applies the complete token-lock side of delegated-stake withdrawal finalization. Keeping this as one production-wired transition makes
    * the one-principal/one-artifact invariant testable across balance, active-lock, pending-state, and artifact outputs.
    */
  private[snapshot] def applyDelegatedStakeTokenLockTransition[F[_]: Async](
    epochProgress: EpochProgress,
    currentSnapshotOrdinal: SnapshotOrdinal,
    fixingDelegatedStakeDoubleWithdrawalOrdinal: SnapshotOrdinal,
    removingProcessedDelegatedStakeWithdrawalsOrdinal: SnapshotOrdinal,
    currentBalances: SortedMap[Address, Balance],
    acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    activeGlobalTokenLocksByRef: Map[Hash, Signed[TokenLock]],
    generatedTokenUnlocksBeforeNaturalExpiryDedup: Map[Address, List[TokenUnlock]],
    expiredWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    updatedWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
  )(implicit hasher: Hasher[F]): F[Either[BalanceArithmeticError, DelegatedStakeTokenLockTransition]] = {
    val fixActive = currentSnapshotOrdinal >= fixingDelegatedStakeDoubleWithdrawalOrdinal
    val naturallyExpiredTokenLockRefs =
      if (fixActive)
        activeGlobalTokenLocksByRef.iterator.collect {
          case (tokenLockRef, tokenLock) if tokenLock.unlockEpoch.exists(_ < epochProgress) => tokenLockRef
        }.toSet
      else Set.empty[Hash]
    val generatedTokenUnlocks = excludeNaturallyExpiredDelegatedStakeUnlocks(
      generatedTokenUnlocksBeforeNaturalExpiryDedup,
      naturallyExpiredTokenLockRefs,
      fixActive
    )
    val expiredTokenLocks = filterExpiredTokenLocks(lastActiveGlobalTokenLocks, epochProgress)
    val processedWithdrawalRefsByAddress =
      if (currentSnapshotOrdinal >= removingProcessedDelegatedStakeWithdrawalsOrdinal)
        expiredWithdrawals.view.mapValues(_.toList.map(_.event.value.tokenLockRef).toSet).toMap
      else Map.empty[Address, Set[Hash]]
    val cleanedWithdrawals = updatedWithdrawals.map {
      case (address, withdrawals) =>
        val processedRefs = processedWithdrawalRefsByAddress.getOrElse(address, Set.empty[Hash])
        if (processedRefs.isEmpty) address -> withdrawals
        else address -> withdrawals.filterNot(w => processedRefs.contains(w.event.value.tokenLockRef))
    }.filter { case (_, withdrawals) => withdrawals.nonEmpty }

    for {
      activeTokenLocks <- acceptTokenLocks(
        epochProgress,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocks
      )
      naturallyExpiredArtifacts <- emitTokenUnlocks(expiredTokenLocks)
    } yield
      updateGlobalBalancesByTokenLocks(
        epochProgress,
        currentBalances,
        acceptedGlobalTokenLocks,
        lastActiveGlobalTokenLocks,
        generatedTokenUnlocks
      ).map { balances =>
        val generatedArtifacts = SortedSet.from[SharedArtifact](
          generatedTokenUnlocks.view.values.flatten.filterNot(unlock =>
            naturallyExpiredArtifacts.exists {
              case naturalUnlock: TokenUnlock => naturalUnlock.tokenLockRef == unlock.tokenLockRef
              case _                          => false
            }
          )
        )

        DelegatedStakeTokenLockTransition(
          generatedTokenUnlocks,
          activeTokenLocks,
          balances,
          cleanedWithdrawals,
          naturallyExpiredArtifacts,
          generatedArtifacts
        )
      }
  }

  private def acceptTokenLocks[F[_]: Async](
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
          val unlocksRefs = generatedTokenUnlocksByAddress.getOrElse(address, List.empty).map(_.tokenLockRef)

          unexpired
            .foldM(SortedSet.empty[Signed[TokenLock]]) { (kept, tokenLock) =>
              tokenLock.toHashed.map(hashed => if (unlocksRefs.contains(hashed.hash)) kept else kept + tokenLock)
            }
            .map(updatedLocks => acc.updated(address, updatedLocks))
      }
      .map(_.filterNot(_._2.isEmpty))
  }

  private def filterExpiredTokenLocks(
    tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
    tokenLocks.view.mapValues(_.filter(_.unlockEpoch.exists(_ < epochProgress))).to(SortedMap)

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
          unexpiredBalance <- tokenLocks
            .filter(_.unlockEpoch.forall(_ >= epochProgress))
            .foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (balanceEither, tokenLock) =>
              for {
                balance <- balanceEither
                afterAmount <- balance.minus(TokenLockAmount.toAmount(tokenLock.amount))
                afterFee <- afterAmount.minus(TokenLockFee.toAmount(tokenLock.fee))
              } yield afterFee
            }
          expiredBalance <- tokenLocks
            .filter(_.unlockEpoch.exists(_ < epochProgress))
            .foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (balanceEither, tokenLock) =>
              balanceEither.flatMap(_.plus(TokenLockAmount.toAmount(tokenLock.amount)))
            }
          finalBalance <- generatedTokenUnlocksByAddress
            .getOrElse(address, List.empty)
            .foldLeft[Either[BalanceArithmeticError, Balance]](Right(expiredBalance)) { (balanceEither, tokenUnlock) =>
              balanceEither.flatMap(_.plus(TokenLockAmount.toAmount(tokenUnlock.amount)))
            }
        } yield acc.updated(address, finalBalance)
    }
  }

  private def emitTokenUnlocks[F[_]: Async](
    expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
  )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
    expiredTokenLocks.values.flatten.toList
      .traverse(_.toHashed)
      .map(hashedLocks =>
        SortedSet.from[SharedArtifact](hashedLocks.map(hashed => TokenUnlock(hashed.hash, hashed.amount, hashed.currencyId, hashed.source)))
      )

  private[snapshot] def filterExpiredGlobalAllowSpends[F[_]: Async](
    allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    epochProgress: EpochProgress,
    globalSpendTransactions: List[SpendTransaction],
    suppressSpent: Boolean
  )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] = {
    val expiredAllowSpends = allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap)

    if (!suppressSpent) expiredAllowSpends.pure[F]
    else {
      val spentAllowSpendHashes = globalSpendTransactions.flatMap(_.allowSpendRef).toSet

      expiredAllowSpends.toList.traverse {
        case (address, signedAllowSpends) =>
          signedAllowSpends.toList
            .traverse(_.toHashed)
            .map(_.filterNot(hashed => spentAllowSpendHashes.contains(hashed.hash)).map(_.signed).toSortedSet)
            .map(address -> _)
      }
        .map(_.to(SortedMap))
    }
  }

  private[snapshot] def updateGlobalBalancesByAllowSpends(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    refundableExpiredGlobalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
  ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] =
    (globalAllowSpends |+| refundableExpiredGlobalAllowSpends)
      .foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
        case (accEither, (address, allowSpends)) =>
          for {
            acc <- accEither
            initialBalance = acc.getOrElse(address, Balance.empty)

            unexpiredBalance <- allowSpends
              .filter(_.lastValidEpochProgress >= epochProgress)
              .foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                  balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                } yield balanceAfterFee
              }

            expiredBalance <- allowSpends
              .filter(_.lastValidEpochProgress < epochProgress)
              .foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                } yield balanceAfterExpiredAmount
              }
          } yield acc.updated(address, expiredBalance)
      }

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
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

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
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
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

      val fixingAllowSpendAndTokenLockValidation = fieldsAddedOrdinals.fixingAllowSpendAndTokenLockValidation
        .getOrElse(environment, SnapshotOrdinal.MinValue)

      val removingProcessedDelegatedStakeWithdrawalsOrdinal = fieldsAddedOrdinals.removingProcessedDelegatedStakeWithdrawals
        .getOrElse(environment, SnapshotOrdinal.MinValue)

      val fixingDelegatedStakeDoubleWithdrawalOrdinal = fieldsAddedOrdinals.fixingDelegatedStakeDoubleWithdrawal
        .getOrElse(environment, SnapshotOrdinal.unsafeApply(Long.MaxValue))
      val preventingAllowSpendResurrectionOrdinal = fieldsAddedOrdinals.preventingAllowSpendResurrection
        .getOrElse(environment, SnapshotOrdinal.MinValue)

      // Below the activation ordinal the retired-reference ledger is neither read nor written, so signed history
      // replays byte-identically: the new GlobalSnapshotInfo field stays None and JsonSerializer drops nulls.
      val preventAllowSpendResurrection = ordinal > preventingAllowSpendResurrectionOrdinal

      val fixingGlobalAllowSpendExpiration = fieldsAddedOrdinals.fixingGlobalAllowSpendExpiration
        .getOrElse(environment, SnapshotOrdinal.unsafeApply(Long.MaxValue))

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

        sharedArtifacts: MapView[Address, List[SharedArtifact]] =
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

        (acceptedSpendActions, rejectedSpendActions) <- spendActionValidator.validateReturningAcceptedAndRejected(
          spendActions,
          lastActiveAllowSpends,
          currencyBalances ++ globalBalances
        )

        _ <- logger.debug(s"--- [ORDINAL=$ordinal] Accepted spend actions: ${acceptedSpendActions.show}")
        _ <- logger.debug(s"--- [ORDINAL=$ordinal] Rejected spend actions: ${rejectedSpendActions.show}")

        (acceptedPricingUpdates, rejectedPricingUpdates) <- pricingUpdateValidator.validateReturningAcceptedAndRejected(
          pricingUpdates,
          lastSnapshotContext,
          epochProgress
        )

        _ <- logger.debug(s"--- Accepted pricing updates: ${acceptedPricingUpdates.show}")
        _ <- logger.debug(s"--- Rejected pricing updates: ${rejectedPricingUpdates.show}")

        sCSnapshotHashes <- scSnapshots.toList.traverse {
          case (address, nel) => nel.last.toHashed.map(address -> _.hash)
        }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
        updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

        allowSpendBlockAcceptanceResult <- acceptAllowSpendBlocks(
          allowSpendBlocksForAcceptance,
          lastSnapshotContext,
          ordinal,
          fixingAllowSpendAndTokenLockValidation,
          epochProgress,
          allowSpendBlockAcceptanceMode
        )

        tokenLockBlockAcceptanceResult <- acceptTokenLockBlocks(
          tokenLockBlocksForAcceptance,
          lastSnapshotContext,
          ordinal,
          fixingAllowSpendAndTokenLockValidation,
          epochProgress
        )

        acceptedGlobalAllowSpends = allowSpendBlockAcceptanceResult.accepted.flatMap(_.value.transactions.toList)
        acceptedGlobalTokenLocks = tokenLockBlockAcceptanceResult.accepted.flatMap(_.value.tokenLocks.toList)

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

        allAcceptedSpendTxns =
          acceptedSpendActions.values.flatten
            .flatMap(spendAction => spendAction.spendTransactions.toList)
            .toList

        globalSpendTransactions = allAcceptedSpendTxns.filter(_.currencyId.isEmpty)

        globalActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )
        lastActiveGlobalAllowSpends = globalActiveAllowSpends.getOrElse(
          None,
          SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]]
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

        globalRetiredAllowSpendRefs = lastSnapshotContext.retiredAllowSpendRefs.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]]
        )

        (updatedAllowSpends, updatedRetiredAllowSpendRefs) <- AllowSpendAcceptance.acceptAllowSpends(
          epochProgress,
          activeAllowSpendsFromCurrencySnapshots,
          globalAllowSpends,
          globalActiveAllowSpends,
          allAcceptedSpendTxns,
          globalRetiredAllowSpendRefs,
          preventAllowSpendResurrection
        )

        updatedAllowSpendRefs = acceptAllowSpendRefs(
          globalLastAllowSpendRefs,
          allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
        )

        refundableExpiredGlobalAllowSpends <- filterExpiredGlobalAllowSpends(
          lastActiveGlobalAllowSpends,
          epochProgress,
          globalSpendTransactions,
          ordinal >= fixingGlobalAllowSpendExpiration
        )

        updatedBalancesByAllowSpends <- Async[F].fromEither(
          updateGlobalBalancesByAllowSpends(
            epochProgress,
            updatedBalancesByRewards,
            globalAllowSpends,
            refundableExpiredGlobalAllowSpends
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

        expiredWithdrawalsForUnlock =
          if (ordinal >= removingProcessedDelegatedStakeWithdrawalsOrdinal) {
            expiredWithdrawalsDelegatedStaking.map {
              case (address, withdrawals) =>
                address -> withdrawals.filter(w => globalActiveTokenLocksByRef.contains(w.event.value.tokenLockRef))
            }.filter { case (_, withdrawals) => withdrawals.nonEmpty }
          } else expiredWithdrawalsDelegatedStaking

        _ <-
          if (ordinal >= removingProcessedDelegatedStakeWithdrawalsOrdinal) {
            val orphans = expiredWithdrawalsDelegatedStaking.flatMap {
              case (address, withdrawals) =>
                withdrawals.toList.collect {
                  case w if !globalActiveTokenLocksByRef.contains(w.event.value.tokenLockRef) =>
                    (address, w.event.value.tokenLockRef)
                }
            }
            if (orphans.nonEmpty)
              logger.warn(
                s"[ORDINAL=$ordinal] Skipping token unlock generation for orphan delegated stake withdrawals " +
                  s"(token lock already removed in a prior snapshot). Pairs: ${orphans.toList}"
              )
            else Async[F].unit
          } else Async[F].unit

        generatedTokenUnlocksBeforeNaturalExpiryDedup <- generateDelegatedStakeTokenUnlocks(
          expiredWithdrawalsForUnlock,
          globalActiveTokenLocksByRef,
          ordinal,
          fixingDelegatedStakeDoubleWithdrawalOrdinal
        ) match {
          case Right(tokenUnlocks) => tokenUnlocks.pure[F]
          case Left(error) =>
            val orphans = expiredWithdrawalsForUnlock.flatMap {
              case (address, withdrawals) =>
                withdrawals.toList.collect {
                  case w if !globalActiveTokenLocksByRef.contains(w.event.value.tokenLockRef) =>
                    (address, w.event.value.tokenLockRef)
                }
            }
            logger.error(
              s"[ORDINAL=$ordinal] Error when generating token unlocks: $error. " +
                s"Orphan (address -> missing tokenLockRef) pairs: ${orphans.toList}"
            ) >>
              MonadThrow[F].raiseError[Map[Address, List[TokenUnlock]]](
                new RuntimeException(s"Error when generating token unlocks: $error")
              )
        }

        delegatedStakeTokenLockTransition <- applyDelegatedStakeTokenLockTransition(
          epochProgress,
          ordinal,
          fixingDelegatedStakeDoubleWithdrawalOrdinal,
          removingProcessedDelegatedStakeWithdrawalsOrdinal,
          updatedBalancesByAllowSpends,
          globalTokenLocks,
          globalActiveTokenLocks,
          globalActiveTokenLocksByRef,
          generatedTokenUnlocksBeforeNaturalExpiryDedup,
          expiredWithdrawalsDelegatedStaking,
          updatedWithdrawDelegatedStakes
        ).flatMap {
          case Right(transition) => transition.pure[F]
          case Left(error) =>
            Async[F].raiseError[DelegatedStakeTokenLockTransition](
              new RuntimeException(s"Balance arithmetic error updating balances by token locks: $error")
            )
        }

        updatedGlobalTokenLocks = delegatedStakeTokenLockTransition.activeTokenLocks

        updatedTokenLockRefs = acceptTokenLockRefs(
          globalLastTokenLockRefs,
          tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs
        )

        updatedTokenLockBalances = updateTokenLockBalances(
          currencySnapshots,
          lastSnapshotContext.tokenLockBalances
        )

        updatedBalancesByTokenLocks = delegatedStakeTokenLockTransition.balances

        allGlobalAllowSpends <- (globalAllowSpends |+| lastActiveGlobalAllowSpends).toList.traverse {
          case (address, allowSpends) =>
            allowSpends.toList.traverse(_.toHashed).map(address -> _)
        }.map(_.toSortedMap)

        updatedBalancesBySpendTransactions = updateGlobalBalancesBySpendTransactions(
          updatedBalancesByTokenLocks,
          allGlobalAllowSpends,
          globalSpendTransactions,
          preventAllowSpendResurrection
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
        updatedWithdrawDelegatedStakesCleaned = delegatedStakeTokenLockTransition.pendingWithdrawals
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

        updatedAcceptedMetagraphSyncData <- acceptMetagraphSyncData(
          lastSnapshotContext,
          incomingCurrencySnapshots,
          globalSnapshotsProcessed,
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
          if (ordinal < metagraphSyncDataStartingOrdinal) none else updatedAcceptedMetagraphSyncData.some,
          if (!preventAllowSpendResurrection) none else updatedRetiredAllowSpendRefs.some
        )

        stateProof <- gsi.stateProof(maybeMerkleTree)

        allowSpendsExpiredEvents <- emitAllowSpendsExpired(
          refundableExpiredGlobalAllowSpends
        )

        tokenUnlocksEvents = delegatedStakeTokenLockTransition.naturallyExpiredArtifacts
        generatedTokenUnlockArtifacts = delegatedStakeTokenLockTransition.generatedArtifacts

        _ <- logger
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

    /** The `Some(allowSpend)` branch credits the destination and refunds the escrow remainder to the source without any matching debit -
      * the debit happened once, when the allow-spend was created. That only conserves value if a given reference is honored exactly once,
      * so once an allow-spend has been settled it is removed from the lookup table and a repeat of the same reference falls through to the
      * plain debit-and-credit branch instead of minting.
      */
    private def updateGlobalBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      globalSpendTransactions: List[SpendTransaction],
      consumeSettledAllowSpends: Boolean
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] =
      globalSpendTransactions
        .foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, List[Hashed[AllowSpend]]])]](
          Right((currentBalances, allGlobalAllowSpends))
        ) { (innerAccEither, spendTransaction) =>
          for {
            (innerAcc, availableAllowSpends) <- innerAccEither
            destinationAddress = spendTransaction.destination
            sourceAddress = spendTransaction.source

            addressAllowSpends = availableAllowSpends.getOrElse(sourceAddress, List.empty)
            spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
            currentDestinationBalance = innerAcc.getOrElse(destinationAddress, Balance.empty)

            updatedBalances <- spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
              addressAllowSpends.find(_.hash === allowSpendRef)
            } match {
              case Some(allowSpend) =>
                val sourceAllowSpendAddress = allowSpend.source
                val currentSourceBalance = innerAcc.getOrElse(sourceAllowSpendAddress, Balance.empty)
                val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value

                val remainingAllowSpends =
                  if (consumeSettledAllowSpends)
                    availableAllowSpends.updated(sourceAddress, addressAllowSpends.filterNot(_.hash === allowSpend.hash))
                  else
                    availableAllowSpends

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.plus(
                    Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue))
                  )
                } yield
                  (
                    innerAcc
                      .updated(destinationAddress, updatedDestinationBalance)
                      .updated(sourceAllowSpendAddress, updatedSourceBalance),
                    remainingAllowSpends
                  )

              case None =>
                val currentSourceBalance = innerAcc.getOrElse(sourceAddress, Balance.empty)

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.minus(spendTransactionAmount)
                } yield
                  (
                    innerAcc
                      .updated(destinationAddress, updatedDestinationBalance)
                      .updated(sourceAddress, updatedSourceBalance),
                    availableAllowSpends
                  )
            }
          } yield updatedBalances
        }
        .map { case (balances, _) => balances }

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
      snapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
      epochProgress: EpochProgress,
      acceptanceMode: AllowSpendBlockAcceptanceMode
    )(implicit hasher: Hasher[F]) = {
      val context = AllowSpendBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastAllowSpendRefs.getOrElse(Map.empty),
        collateral,
        AllowSpendReference.empty
      )
      if (snapshotOrdinal > fixingAllowSpendAndTokenLockValidation) {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral = true,
          epochProgress.some,
          acceptanceMode.creditDestination
        )
      } else {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral = true,
          none,
          acceptanceMode.creditDestination
        )
      }
    }

    private def acceptTokenLockBlocks(
      blocksForAcceptance: List[Signed[TokenLockBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
      epochProgress: EpochProgress
    )(implicit hasher: Hasher[F]) = {
      val context = TokenLockBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTokenLockRefs.getOrElse(Map.empty),
        collateral,
        TokenLockReference.empty
      )
      if (snapshotOrdinal > fixingAllowSpendAndTokenLockValidation) {
        tokenLockBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral = true,
          epochProgress.some
        )
      } else {
        tokenLockBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral = true,
          none
        )
      }
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
      globalSnapshotsProcessed: Map[Address, List[GlobalSnapshotsProcessed]],
      acceptedSpendActions: Map[Address, List[SpendAction]],
      currentGlobalOrdinal: SnapshotOrdinal,
      currentGlobalEpochProgress: EpochProgress
    ): F[SortedMap[Address, MetagraphSyncDataInfo]] =
      lastSnapshotContext.metagraphSyncData.map { existingData =>
        for {
          updatedFromSnapshots <- updateFromCurrencySnapshots(
            existingData,
            incomingCurrencySnapshots,
            globalSnapshotsProcessed,
            currentGlobalOrdinal,
            currentGlobalEpochProgress
          )

          updatedFromSpendActions <- updateFromSpendActions(
            updatedFromSnapshots,
            acceptedSpendActions,
            currentGlobalOrdinal
          )

        } yield updatedFromSpendActions
      }.getOrElse(SortedMap.empty[Address, MetagraphSyncDataInfo].pure[F])

    private def updateFromCurrencySnapshots(
      existingData: SortedMap[Address, MetagraphSyncDataInfo],
      incomingCurrencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
      globalSnapshotsProcessed: Map[Address, List[GlobalSnapshotsProcessed]],
      currentOrdinal: SnapshotOrdinal,
      currentEpochProgress: EpochProgress
    ): F[SortedMap[Address, MetagraphSyncDataInfo]] =
      incomingCurrencySnapshots.toList.traverse {
        case (address, snapshots) =>
          val currentInfo = existingData.getOrElse(address, MetagraphSyncDataInfo.empty)
          val metagraphGlobalSnapshotsProcessed =
            globalSnapshotsProcessed.getOrElse(address, List.empty).flatMap(_.ordinals).toSet
          val updatedUnappliedGlobalChangeOrdinals =
            currentInfo.unappliedGlobalChangeOrdinals.diff(metagraphGlobalSnapshotsProcessed)

          val updatedInfo = currentInfo
            .focus(_.globalOrdinalLastAcceptedOn)
            .replace(currentOrdinal)
            .focus(_.globalEpochProgressLastAcceptedOn)
            .replace(currentEpochProgress)
            .focus(_.unappliedGlobalChangeOrdinals)
            .replace(updatedUnappliedGlobalChangeOrdinals)

          (address -> updatedInfo).pure[F]
      }.map { updatedEntries =>
        val updatedMap = SortedMap.from(updatedEntries)
        existingData ++ updatedMap
      }

    private def updateFromSpendActions(
      currentData: SortedMap[Address, MetagraphSyncDataInfo],
      spendActions: Map[Address, List[SpendAction]],
      currentOrdinal: SnapshotOrdinal
    ): F[SortedMap[Address, MetagraphSyncDataInfo]] = {
      val allCurrencySpendTransactions = extractCurrencySpendTransactions(spendActions)

      val transactionsByMetagraph = allCurrencySpendTransactions.groupBy(_.currencyId.get.value)

      transactionsByMetagraph.toList.foldM(currentData) {
        case (acc, (metagraphId, transactions)) =>
          val currentInfo = acc.getOrElse(metagraphId, MetagraphSyncDataInfo.empty)

          val updatedUnappliedGlobalChangeOrdinals =
            trimUnappliedOrdinals(currentInfo.unappliedGlobalChangeOrdinals, currentOrdinal)

          val updatedInfo = currentInfo
            .focus(_.unappliedGlobalChangeOrdinals)
            .replace(updatedUnappliedGlobalChangeOrdinals)

          acc.updated(metagraphId, updatedInfo).pure[F]
      }
    }

    private def extractCurrencySpendTransactions(spendActions: Map[Address, List[SpendAction]]): List[SpendTransaction] =
      spendActions.values.flatten
        .flatMap(_.spendTransactions.toList)
        .filter(_.currencyId.isDefined)
        .toList

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
