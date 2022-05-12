package org.tessellation.dag.snapshot

import cats.data.NonEmptyList
import cats.effect.{Async, Concurrent}
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex

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
  blocks: Set[BlockAsActiveTip],
  stateChannelSnapshots: Map[Address, NonEmptyList[StateChannelSnapshotBinary]],
  rewards: Set[RewardTransaction],
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfo,
  tips: GlobalSnapshotTips
) {

  def activeTips[F[_]: Async: KryoSerializer]: F[Set[ActiveTip]] =
    blocks.toList.traverse { blockAsActiveTip =>
      BlockReference
        .of(blockAsActiveTip.block)
        .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
    }.map(_.toSet.union(tips.remainedActive))

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
      Set.empty,
      Map.empty,
      Set.empty,
      NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
      GlobalSnapshotInfo(Map.empty, Map.empty, balances),
      GlobalSnapshotTips(
        Set.empty[DeprecatedTip],
        mkActiveTips(16)
      )
    )

  private def mkActiveTips(n: Int): Set[ActiveTip] =
    List
      .range(0, n)
      .map { i =>
        ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
      }
      .toSet
}
