package org.tessellation.sdk.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection.sortedSetSyntax
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.tessellation.sdk.infrastructure.snapshot.CurrencyDataCreator.CurrencyDataCreationResult

trait CurrencyDataCreator[F[_]] {

  def createCurrencyData(
    lastCurrencyData: CurrencyData,
    lastCurrencyInfo: CurrencyDataInfo,
    lastOrdinal: SnapshotOrdinal,
    lastSigners: NonEmptySet[Id],
    blocks: Set[Signed[Block]],
    mintRewards: Boolean
  ): F[CurrencyDataCreationResult]

}

object CurrencyDataCreator {

  case class CurrencyDataCreationResult(
    data: CurrencyData,
    info: CurrencyDataInfo,
    awaitingBlocks: Set[Signed[Block]],
    rejectedBlocks: Set[Signed[Block]]
  )

  def make[F[_]: Async: KryoSerializer](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    rewards: Rewards[F]
  ): CurrencyDataCreator[F] = new CurrencyDataCreator[F] {
    def createCurrencyData(
      lastCurrencyData: CurrencyData,
      lastCurrencyInfo: CurrencyDataInfo,
      lastOrdinal: SnapshotOrdinal,
      lastSigners: NonEmptySet[Id],
      blocks: Set[Signed[Block]],
      mintRewards: Boolean
    ): F[CurrencyDataCreationResult] =
      for {
        lastActiveTips <- lastCurrencyData.activeTips(lastOrdinal)
        lastDeprecatedTips = lastCurrencyData.tips.deprecated

        tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)

        blockAcceptanceResult <- blockAcceptanceManager.acceptBlocksIteratively(
          blocks.toList,
          BlockAcceptanceContext.fromStaticData(
            lastCurrencyInfo.balances,
            lastCurrencyInfo.lastTxRefs,
            tipUsages
          )
        )

        ordinal = lastOrdinal.next
        (tips, acceptedBlocks) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          blockAcceptanceResult,
          ordinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastCurrencyData, tips, acceptedBlocks)

        updatedBalancesByRegularTxs = (lastCurrencyInfo.balances ++ blockAcceptanceResult.contextUpdate.balances).filter {
          case (_, balance) => balance =!= Balance.empty
        }

        acceptedTransactions = blockAcceptanceResult.accepted.flatMap {
          case (block, _) => block.value.transactions.toSortedSet
        }.toSortedSet

        rewardTxsForAcceptance <- rewards.distribute(lastCurrencyData, lastSigners, acceptedTransactions, mintRewards)

        (updatedBalancesByRewardTxs, acceptedRewardTxs) = acceptRewardTxs(updatedBalancesByRegularTxs, rewardTxsForAcceptance)

        updatedLastTxRefs = lastCurrencyInfo.lastTxRefs ++ blockAcceptanceResult.contextUpdate.lastTxRefs

        epochProgress =
          if (mintRewards)
            lastCurrencyData.epochProgress.next
          else
            lastCurrencyData.epochProgress

        (awaitingBlocks, rejectedBlocks) = blockAcceptanceResult.notAccepted.partitionMap {
          case (b, _: BlockAwaitReason)     => b.asRight
          case (b, _: BlockRejectionReason) => b.asLeft
        }

        data = CurrencyData(
          height,
          subHeight,
          acceptedBlocks,
          acceptedRewardTxs,
          tips,
          epochProgress
        )

        dataInfo = CurrencyDataInfo(
          updatedLastTxRefs,
          updatedBalancesByRewardTxs
        )
      } yield
        CurrencyDataCreationResult(
          data,
          dataInfo,
          awaitingBlocks.toSet,
          rejectedBlocks.toSet
        )

    private def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

    protected def getUpdatedTips(
      lastActive: SortedSet[ActiveTip],
      lastDeprecated: SortedSet[DeprecatedTip],
      acceptanceResult: BlockAcceptanceResult,
      currentOrdinal: SnapshotOrdinal
    ): (SnapshotTips, SortedSet[BlockAsActiveTip]) = {
      val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
      val accepted =
        acceptanceResult.accepted.map { case (block, usages) => BlockAsActiveTip(block, usages) }.toSortedSet
      val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
        val maybeUpdatedUsage = usagesUpdate.get(at.block)
        Either.cond(
          maybeUpdatedUsage.exists(_ >= deprecationThreshold),
          DeprecatedTip(at.block, currentOrdinal),
          maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
        )
      }.bimap(_.toSortedSet, _.toSortedSet)
      val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
      val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

      (SnapshotTips(remainedDeprecated | newlyDeprecated, remainedActive), accepted)
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

    protected def getHeightAndSubHeight(
      lastCommonSnapshot: CurrencyData,
      tips: SnapshotTips,
      accepted: Set[BlockAsActiveTip]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (tips.deprecated.map(_.block.height) ++ tips.remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastCommonSnapshot.height)
            InvalidHeight(lastCommonSnapshot.height, height).raiseError
          else
            Applicative[F].unit

        subHeight = if (height === lastCommonSnapshot.height) lastCommonSnapshot.subHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }
  }

}
