package org.tessellation.infrastructure

import java.util.Base64

import cats.data.NonEmptyList
import cats.syntax.either._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.snapshot._
import org.tessellation.domain.aci.StateChannelGistedOutput
import org.tessellation.domain.snapshot.SnapshotTrigger
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._

package object snapshot {

  type DAGEvent = Either[Signed[DAGBlock], SnapshotTrigger]

  type StateChannelEvent = StateChannelGistedOutput[StateChannelSnapshot]

  type GlobalSnapshotEvent = Either[StateChannelEvent, DAGEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalSnapshot

  def toEvent(trigger: SnapshotTrigger): GlobalSnapshotEvent =
    trigger.asRight[Signed[DAGBlock]].asRight[StateChannelEvent]

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
      Hash(""),
      Set.empty,
      Map(
        Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS") -> NonEmptyList.one(
          StateChannelSnapshotBinary(
            Hash(""),
            Base64.getDecoder.decode(
              "6gcBbGFzdFNuYXBzaG90SGFz6EMDwQE2NDk3Mzg0NDNmZjM0MDY4ZjI2NzQyODQyMGU0MmNiY2M3OTgyNGJlYTdlMDA0ZmFmZmJjYTY3ZmRkYWQzZjA4AA=="
            )
          )
        )
      ),
      genesisNextFacilitators,
      GlobalSnapshotInfo(
        Map(
          Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS") ->
            Hash("649738443ff34068f267428420e42cbcc79824bea7e004faffbca67fddad3f08")
        )
      )
    )

  val signedGenesis: Signed[GlobalSnapshot] =
    Signed(genesis, NonEmptyList.one(SignatureProof(Id(Hex("peer1")), Signature(Hex("signature1")))))

}
