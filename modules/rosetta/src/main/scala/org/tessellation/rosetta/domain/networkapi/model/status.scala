package org.tessellation.rosetta.domain.networkapi.model

import cats.syntax.eq._

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.customizableEncoder
import derevo.derive
import enumeratum.values.{StringCirceEnum, StringEnum, StringEnumEntry}

object status {
  @derive(customizableEncoder, eqv, show)
  case class BlockIdentifier(
    index: SnapshotOrdinal,
    hash: Hex
  )

  @derive(eqv, show)
  sealed abstract class Stage(val value: String) extends StringEnumEntry

  object Stage extends StringEnum[Stage] with StringCirceEnum[Stage] {
    val values = findValues

    case object Ready extends Stage(value = "ready")
    case object NotReady extends Stage(value = "not ready")

    def isSynced(stage: Stage): Boolean =
      stage === Ready

    def fromNodeState(nodeState: NodeState): Stage =
      if (nodeState === NodeState.Ready)
        Stage.Ready
      else
        Stage.NotReady
  }

  @derive(customizableEncoder, eqv, show)
  case class SyncStatus(
    currentIndex: Long,
    targetIndex: Long,
    stage: Stage,
    synced: Boolean
  )

  @derive(customizableEncoder, eqv, show)
  case class RosettaPeerId(peerId: PeerId)

  @derive(customizableEncoder, eqv, show)
  case class NetworkStatusResponse(
    currentBlockIdentifier: BlockIdentifier,
    currentBlockTimestamp: Long,
    genesisBlockIdentifier: BlockIdentifier,
    oldestBlockIdentifier: BlockIdentifier,
    syncStatus: SyncStatus,
    peers: List[RosettaPeerId]
  )

}
