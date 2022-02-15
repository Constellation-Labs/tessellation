package org.tessellation.infrastructure

import cats.data.NonEmptyList

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

package object snapshot {

  val genesisNextFacilitators: NonEmptyList[PeerId] = NonEmptyList
    .of(
      PeerId(Hex("peer1")),
      PeerId(Hex("peer2")),
      PeerId(Hex("peer3"))
    )

  val genesis: GlobalSnapshot =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Hash("0"),
      Set.empty,
      Map.empty,
      genesisNextFacilitators
    )

}
