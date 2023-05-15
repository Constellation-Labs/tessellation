package org.tessellation.schema

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, show, encoder, decoder)
case class CurrencyData(
  height: Height,
  subHeight: SubHeight,
  blocks: SortedSet[BlockAsActiveTip],
  rewards: SortedSet[RewardTransaction],
  tips: SnapshotTips,
  epochProgress: EpochProgress
) {
  def activeTips[F[_]: Async: KryoSerializer](ordinal: SnapshotOrdinal): F[SortedSet[ActiveTip]] =
    blocks.toList.traverse { blockAsActiveTip =>
      BlockReference
        .of(blockAsActiveTip.block)
        .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
    }.map(_.toSortedSet.union(tips.remainedActive))
}
