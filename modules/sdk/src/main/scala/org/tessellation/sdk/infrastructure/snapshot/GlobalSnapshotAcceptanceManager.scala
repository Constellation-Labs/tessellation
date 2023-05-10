package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.merkletree.Proof
import org.tessellation.merkletree.syntax._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.{DAGTransaction, RewardTransaction}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait GlobalSnapshotAcceptanceManager[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    blocksForAcceptance: List[Signed[DAGBlock]],
    scEvents: List[StateChannelOutput],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[DAGTransaction]] => F[SortedSet[RewardTransaction]]
  ): F[
    (
      BlockAcceptanceResult[DAGBlock],
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput],
      SortedSet[RewardTransaction],
      GlobalSnapshotInfo,
      GlobalSnapshotStateProof
    )
  ]
}

object GlobalSnapshotAcceptanceManager {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: KryoSerializer](
    blockAcceptanceManager: BlockAcceptanceManager[F, DAGTransaction, DAGBlock],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount
  ) = new GlobalSnapshotAcceptanceManager[F] {

    def accept(
      ordinal: SnapshotOrdinal,
      blocksForAcceptance: List[Signed[DAGBlock]],
      scEvents: List[StateChannelOutput],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[DAGTransaction]] => F[SortedSet[RewardTransaction]]
    ) = for {
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)

      (scSnapshots, currencySnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(ordinal, lastSnapshotContext, scEvents)
      sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.toHashed.map(address -> _.hash) }
        .map(_.toMap)
      updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
      updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances,
        rewards
      )

      maybeMerkleTree <- updatedLastCurrencySnapshots.merkleTree[F]
      updatedLastCurrencySnapshotProofs <- maybeMerkleTree.traverse { merkleTree =>
        updatedLastCurrencySnapshots.toList.traverse {
          case (address, state) =>
            (address, state).hashF
              .map(merkleTree.findPath(_))
              .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
              .map((address, _))
        }
      }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))

      gsi = GlobalSnapshotInfo(
        updatedLastStateChannelSnapshotHashes,
        transactionsRefs,
        updatedBalancesByRewards,
        updatedLastCurrencySnapshots,
        updatedLastCurrencySnapshotProofs
      )

      stateProof <- gsi.stateProof(maybeMerkleTree)

    } yield
      (
        acceptanceResult,
        scSnapshots,
        returnedSCEvents,
        acceptedRewardTxs,
        gsi,
        stateProof
      )

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[DAGBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
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
