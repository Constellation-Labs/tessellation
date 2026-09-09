package io.constellationnetwork.node.shared.infrastructure.consensus

object trigger {
  type ConsensusTrigger = io.constellationnetwork.schema.consensus.ConsensusTrigger
  val EventTrigger: io.constellationnetwork.schema.consensus.EventTrigger.type =
    io.constellationnetwork.schema.consensus.EventTrigger
  val TimeTrigger: io.constellationnetwork.schema.consensus.TimeTrigger.type =
    io.constellationnetwork.schema.consensus.TimeTrigger
}
