package org.tessellation.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshotV1, CurrencySnapshotInfoV1}
import org.tessellation.ext.crypto._
import org.tessellation.merkletree.Proof
import org.tessellation.merkletree.syntax._
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.statechannel.StateChannelAcceptanceResult
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.swap._
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.security._
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._

trait GlobalSnapshotAcceptanceManager[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    blocksForAcceptance: List[Signed[Block]],
    scEvents: List[StateChannelOutput],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    validationType: StateChannelValidationType
  ): F[
    (
      BlockAcceptanceResult,
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

  def make[F[_]: Async: HasherSelector](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount
  ) = new GlobalSnapshotAcceptanceManager[F] {

    def accept(
      ordinal: SnapshotOrdinal,
      blocksForAcceptance: List[Signed[Block]],
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
        sCSnapshotHashes <- scSnapshots.toList.traverse {
          case (address, nel) => nel.last.toHashed.map(address -> _.hash)
        }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
        updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

        transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

        acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

        rewards <- calculateRewardsFn(acceptedTransactions)

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
          lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances ++ currencyAcceptanceBalanceUpdate,
          rewards
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
    }

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
        TransactionReference.empty,
        None,
        None,
        AllowSpendReference.empty
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, ordinal)
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
