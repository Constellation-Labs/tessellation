package org.tessellation.dag.snapshot

import cats.data.NonEmptyList
import cats.effect.Concurrent

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import org.http4s.{EntityDecoder, EntityEncoder}

@derive(eqv, show)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: Set[Signed[DAGBlock]],
  stateChannelSnapshots: Map[Address, NonEmptyList[StateChannelSnapshotBinary]],
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfo
)

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
      NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
      GlobalSnapshotInfo(Map.empty, Map.empty, balances)
    )
}
