package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.Parallel
import cats.data.NonEmptySetImpl.catsDataInstancesForNonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.swap.block._
import io.constellationnetwork.node.shared.domain.tokenlock.block._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

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
    fixingAllowSpendDestinationCredit: SnapshotOrdinal,
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
      fixingAllowSpendDestinationCredit: SnapshotOrdinal,
      epochProgress: EpochProgress
    )(implicit hasher: Hasher[F]): F[AllowSpendBlockAcceptanceResult] = {
      val context = AllowSpendBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastAllowSpendRefs.getOrElse(Map.empty),
        collateral,
        AllowSpendReference.empty
      )
      val creditDestination = snapshotOrdinal < fixingAllowSpendDestinationCredit
      if (snapshotOrdinal > fixingAllowSpendAndTokenLockValidation) {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldPerformMetagraphSpecificValidations = true,
          epochProgress.some,
          creditDestination
        )
      } else {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldPerformMetagraphSpecificValidations = true,
          none,
          creditDestination
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
      val replacementTxs = blocksForAcceptance.flatMap(_.value.tokenLocks.toList).filter(_.replaceTokenLockRef.nonEmpty)
      val refHashesBySource = replacementTxs
        .groupBy(_.source)
        .view
        .mapValues(_.flatMap(_.replaceTokenLockRef).toSet)
        .toMap
      val allActiveTokenLocks = lastSnapshotContext.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])

      for {
        toBeReplacedHashedTokenLocks <-
          refHashesBySource.toList.flatTraverse {
            case (address, refHashes) =>
              allActiveTokenLocks
                .getOrElse(address, SortedSet.empty[Signed[TokenLock]])
                .toList
                .traverse(_.toHashed)
                .map(_.filter(h => refHashes.contains(h.hash)))
          }

        context = TokenLockBlockAcceptanceContext.fromStaticData(
          lastSnapshotContext.balances,
          lastSnapshotContext.lastTokenLockRefs.getOrElse(Map.empty),
          collateral,
          TokenLockReference.empty,
          toBeReplacedHashedTokenLocks
        )
        res <-
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
      } yield res
    }
  }
}
