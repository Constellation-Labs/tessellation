package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshotV1, CurrencySnapshotInfoV1}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.block.{
  AllowSpendBlockAcceptanceContext,
  AllowSpendBlockAcceptanceManager,
  AllowSpendBlockAcceptanceResult
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, SpendAction, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendBlock, AllowSpendReference}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.tokenLock.TokenLockAmount.toAmount
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotAcceptanceManager[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    blocksForAcceptance: List[Signed[Block]],
    allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
    scEvents: List[StateChannelOutput],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    validationType: StateChannelValidationType
  ): F[
    (
      BlockAcceptanceResult,
      AllowSpendBlockAcceptanceResult,
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput],
      SortedSet[RewardTransaction],
      GlobalSnapshotInfo,
      GlobalSnapshotStateProof,
      Map[Address, List[SpendAction]]
    )
  ]
}

object GlobalSnapshotAcceptanceManager {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: HasherSelector](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount
  ) = new GlobalSnapshotAcceptanceManager[F] {

    def accept(
      ordinal: SnapshotOrdinal,
      epochProgress: EpochProgress,
      blocksForAcceptance: List[Signed[Block]],
      allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
      scEvents: List[StateChannelOutput],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      validationType: StateChannelValidationType
    ) = {
      implicit val hasher = HasherSelector[F].getForOrdinal(ordinal)

      for {
        acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips, ordinal)

        StateChannelAcceptanceResult(scSnapshots, currencySnapshots, returnedSCEvents, currencyAcceptanceBalanceUpdate) <-
          stateChannelEventsProcessor
            .process(
              ordinal,
              lastSnapshotContext.copy(balances = lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances),
              scEvents,
              validationType
            )

        spendActions = currencySnapshots.toList.map {
          case (_, Left(_))             => Map.empty[Address, List[SharedArtifact]]
          case (address, Right((s, _))) => Map(address -> (s.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).toList))
        }
          .foldLeft(Map.empty[Address, List[SharedArtifact]])(_ |+| _)
          .view
          .mapValues(_.collect { case sa: SpendAction => sa })
          .filter { case (_, actions) => actions.nonEmpty }
          .toMap

        _ <- Slf4jLogger.getLogger[F].debug(s"--- Accepted spend actions: ${spendActions.show}")

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

        acceptedAllowSpends = allowSpendBlockAcceptanceResult.accepted.flatMap(_.value.transactions.toList)

        updatedAllowSpends = lastSnapshotContext.activeAllowSpends.map(
          acceptAllowSpends(epochProgress, currencySnapshots, acceptedAllowSpends, _)
        )

        updatedAllowSpendRefs = lastSnapshotContext.lastAllowSpendRefs.map(
          acceptAllowSpendRefs(_, allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs)
        )

        acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

        transactionsRefs = acceptTransactionRefs(
          lastSnapshotContext.lastTxRefs,
          acceptanceResult.contextUpdate.lastTxRefs,
          acceptedTransactions
        )

        rewards <- calculateRewardsFn(acceptedTransactions)

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
          lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances ++ currencyAcceptanceBalanceUpdate,
          rewards
        )

        currencyTokenLocks = getTokenLocks(
          currencySnapshots
        )

        currencyTokenUnlocks = getTokenUnlocks(
          currencySnapshots
        )

        updatedTokenLockBalances = updateTokenLockBalances(
          currencyTokenLocks,
          currencyTokenUnlocks,
          lastSnapshotContext.tokenLockBalances
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

        gsi = GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          transactionsRefs,
          updatedBalancesByRewards,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs,
          updatedAllowSpends,
          updatedTokenLockBalances.some,
          updatedAllowSpendRefs
        )

        stateProof <- gsi.stateProof(maybeMerkleTree)

      } yield
        (
          acceptanceResult,
          allowSpendBlockAcceptanceResult,
          scSnapshots,
          returnedSCEvents,
          acceptedRewardTxs,
          gsi,
          stateProof,
          spendActions
        )
    }

    private def acceptAllowSpends(
      epochProgress: EpochProgress,
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState],
      acceptedGlobalAllowSpends: List[Signed[AllowSpend]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]] = {
      val allowSpendsFromCurrencySnapshots = currencySnapshots.map { case (key, value) => (key.some, value) }
        .mapFilter(_.toOption.flatMap { case (_, info) => info.activeAllowSpends })

      val globalAllowSpends = acceptedGlobalAllowSpends
        .groupBy(_.value.source)
        .view
        .mapValues(SortedSet.from(_))
        .to(SortedMap)

      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val updatedGlobalAllowSpends = globalAllowSpends.foldLeft(lastActiveGlobalAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      val updatedCurrencyAllowSpends = allowSpendsFromCurrencySnapshots.foldLeft(lastActiveAllowSpends) {
        case (accAllowSpends, (metagraphId, metagraphAllowSpends)) =>
          val lastActiveMetagraphAllowSpends =
            accAllowSpends.getOrElse(metagraphId, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

          val updatedMetagraphAllowSpends =
            metagraphAllowSpends.foldLeft(lastActiveMetagraphAllowSpends) {
              case (accMetagraphAllowSpends, (address, addressAllowSpends)) =>
                val lastAddressAllowSpends = accMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])

                val unexpired = (lastAddressAllowSpends ++ addressAllowSpends)
                  .filter(_.lastValidEpochProgress >= epochProgress)
                accMetagraphAllowSpends + (address -> unexpired)
            }

          accAllowSpends + (metagraphId -> updatedMetagraphAllowSpends)
      }

      if (updatedGlobalAllowSpends.nonEmpty)
        updatedCurrencyAllowSpends + (None -> updatedGlobalAllowSpends)
      else
        updatedCurrencyAllowSpends
    }

    private def getTokenLocks(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState]
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] = currencySnapshots.map {
      case (address, currencySnapshot) =>
        val tokenLocks = currencySnapshot.toOption.map {
          case (snapshot, _) =>
            snapshot.tokenLockBlocks
              .getOrElse(SortedSet.empty[Signed[tokenLock.TokenLockBlock]])
              .flatMap(_.value.tokenLocks.toSortedSet)
        }
          .getOrElse(SortedSet.empty[Signed[TokenLock]])

        address -> tokenLocks
    }

    private def getTokenUnlocks(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState]
    ): SortedMap[Address, SortedSet[TokenUnlock]] =
      currencySnapshots.map {
        case (address, currencySnapshot) =>
          val tokenUnlocks: SortedSet[TokenUnlock] = currencySnapshot.toOption.map {
            case (snapshot, _) =>
              snapshot.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).collect {
                case tokenUnlock: TokenUnlock => tokenUnlock
              }
          }
            .getOrElse(SortedSet.empty)

          address -> tokenUnlocks
      }

    private def updateTokenLockBalances(
      currencyTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      currencyTokenUnlocks: SortedMap[Address, SortedSet[TokenUnlock]],
      maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): SortedMap[Address, SortedMap[Address, Balance]] = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      val balancesUpdatedByTokenLocks = currencyTokenLocks.foldLeft(lastTokenLockBalances) {
        case (accTokenLockBalances, (metagraphId, metagraphTokenLocks)) =>
          val lastMetagraphTokenLocksBalances =
            accTokenLockBalances.getOrElse(metagraphId, SortedMap.empty[Address, Balance])

          val updatedMetagraphTokenLocks = metagraphTokenLocks.foldLeft(lastMetagraphTokenLocksBalances) {
            case (accMetagraphTokenLocks, tokenLock) =>
              val lastAddressTokenLockBalance = accMetagraphTokenLocks.getOrElse(tokenLock.source, Balance.empty)
              val updatedBalance = lastAddressTokenLockBalance
                .plus(toAmount(tokenLock.amount))
                .getOrElse(lastAddressTokenLockBalance)

              accMetagraphTokenLocks.updated(tokenLock.source, updatedBalance)
          }

          accTokenLockBalances + (metagraphId -> updatedMetagraphTokenLocks)
      }

      currencyTokenUnlocks.foldLeft(balancesUpdatedByTokenLocks) {
        case (accTokenLockBalances, (metagraphId, metagraphTokenUnlocks)) =>
          val lastMetagraphTokenLocksBalances =
            accTokenLockBalances.getOrElse(metagraphId, SortedMap.empty[Address, Balance])

          val updatedMetagraphTokenLocksBalances = metagraphTokenUnlocks.foldLeft(lastMetagraphTokenLocksBalances) {
            case (accMetagraphTokenLocksBalances, tokenUnlock) =>
              val lastAddressTokenLockBalance = accMetagraphTokenLocksBalances.getOrElse(tokenUnlock.address, Balance.empty)
              val updatedBalance = lastAddressTokenLockBalance
                .minus(toAmount(tokenUnlock.amount))
                .getOrElse(lastAddressTokenLockBalance)

              accMetagraphTokenLocksBalances.updated(tokenUnlock.address, updatedBalance)
          }

          accTokenLockBalances + (metagraphId -> updatedMetagraphTokenLocksBalances)
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
