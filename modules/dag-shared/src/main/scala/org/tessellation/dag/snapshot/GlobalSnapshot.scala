package org.tessellation.dag.snapshot

import cats.data.NonEmptyList
import cats.effect.{Async, Concurrent}
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.{EntityDecoder, EntityEncoder}

@derive(eqv, show)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[StateChannelSnapshotBinary]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfo,
  tips: GlobalSnapshotTips
) {

  def activeTips[F[_]: Async: KryoSerializer]: F[SortedSet[ActiveTip]] =
    blocks.toList.traverse { blockAsActiveTip =>
      BlockReference
        .of(blockAsActiveTip.block)
        .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
    }.map(_.toSortedSet.union(tips.remainedActive))

}

object GlobalSnapshot {

  implicit def encoder[G[_]: KryoSerializer]: EntityEncoder[G, GlobalSnapshot] =
    BinaryCodec.encoder[G, GlobalSnapshot]

  implicit def decoder[G[_]: Concurrent: KryoSerializer]: EntityDecoder[G, GlobalSnapshot] =
    BinaryCodec.decoder[G, GlobalSnapshot]

  def mkGenesis(balances: Map[Address, Balance]) =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Hash.empty,
      SortedSet.empty,
      SortedMap.empty,
      SortedSet.empty,
      EpochProgress.MinValue,
      NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
      GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.from(balances)),
      GlobalSnapshotTips(
        SortedSet.empty[DeprecatedTip],
        mkActiveTips(16)
      )
    )

  private def mkActiveTips(n: Int): SortedSet[ActiveTip] =
    List
      .range(0, n)
      .map { i =>
        ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
      }
      .toSortedSet

}
