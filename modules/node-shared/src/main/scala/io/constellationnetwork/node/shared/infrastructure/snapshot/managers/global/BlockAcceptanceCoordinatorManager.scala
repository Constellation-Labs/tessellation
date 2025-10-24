package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.swap.block._
import io.constellationnetwork.node.shared.domain.tokenlock.block._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong

trait BlockAcceptanceCoordinatorManager[F[_]] {
  def acceptBlocks(
    blocksForAcceptance: List[Signed[Block]],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    ordinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[BlockAcceptanceResult]

  def acceptAllowSpendBlocks(
    blocksForAcceptance: List[Signed[AllowSpendBlock]],
    lastSnapshotContext: GlobalSnapshotInfo,
    snapshotOrdinal: SnapshotOrdinal,
    fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
    epochProgress: EpochProgress
  )(implicit hasher: Hasher[F]): F[AllowSpendBlockAcceptanceResult]

  def acceptTokenLockBlocks(
    blocksForAcceptance: List[Signed[TokenLockBlock]],
    lastSnapshotContext: GlobalSnapshotInfo,
    snapshotOrdinal: SnapshotOrdinal,
    fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
    epochProgress: EpochProgress
  )(implicit hasher: Hasher[F]): F[TokenLockBlockAcceptanceResult]
}

object BlockAcceptanceCoordinatorManager {

  def make[F[_]: Async: Parallel](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    tipUsageManager: TipUsageManager[F],
    collateral: Amount
  ): BlockAcceptanceCoordinatorManager[F] = new BlockAcceptanceCoordinatorManager[F] {

    def acceptBlocks(
      blocksForAcceptance: List[Signed[Block]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      ordinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]): F[BlockAcceptanceResult] = {
      val tipUsages = tipUsageManager.getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral,
        TransactionReference.empty
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, ordinal)
    }

    def acceptAllowSpendBlocks(
      blocksForAcceptance: List[Signed[AllowSpendBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
      epochProgress: EpochProgress
    )(implicit hasher: Hasher[F]): F[AllowSpendBlockAcceptanceResult] = {
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
          shouldPerformMetagraphSpecificValidations = true,
          epochProgress.some
        )
      } else {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldPerformMetagraphSpecificValidations = true,
          none
        )
      }
    }

    def acceptTokenLockBlocks(
      blocksForAcceptance: List[Signed[TokenLockBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
      epochProgress: EpochProgress
    )(implicit hasher: Hasher[F]): F[TokenLockBlockAcceptanceResult] = {
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
          shouldPerformMetagraphSpecificValidations = true,
          epochProgress.some
        )
      } else {
        tokenLockBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldPerformMetagraphSpecificValidations = true,
          none
        )
      }
    }
  }
}
