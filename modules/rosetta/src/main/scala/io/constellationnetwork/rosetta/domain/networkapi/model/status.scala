package io.constellationnetwork.rosetta.domain.networkapi.model

import cats.syntax.eq._

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

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
