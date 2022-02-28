package org.tessellation.dag.snapshot

import cats.data.NonEmptyList

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: Set[Signed[DAGBlock]],
  balances: Map[Address, Balance],
  stateChannelSnapshots: Map[Address, NonEmptyList[StateChannelSnapshotBinary]],
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfo
)

object GlobalSnapshot {

  def mkGenesis(balances: Map[Address, Balance]) =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Hash.empty,
      Set.empty,
      balances,
      Map.empty,
      NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
      GlobalSnapshotInfo(Map.empty)
    )
}
