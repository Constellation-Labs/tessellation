package io.constellationnetwork.dag.l0.infrastructure.snapshot

import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object event {

  @derive(eqv, decoder, encoder, show)
  sealed trait GlobalSnapshotEvent

  @derive(eqv, decoder, encoder, show)
  case class DAGEvent(value: Signed[Block]) extends GlobalSnapshotEvent

  @derive(eqv, decoder, encoder, show)
  case class StateChannelEvent(value: StateChannelOutput) extends GlobalSnapshotEvent

  @derive(eqv, decoder, encoder, show)
  case class AllowSpendEvent(value: Signed[AllowSpendBlock]) extends GlobalSnapshotEvent

  @derive(eqv, decoder, encoder, show)
  case class TokenLockEvent(value: Signed[TokenLockBlock]) extends GlobalSnapshotEvent

  @derive(eqv, show, encoder, decoder)
  case class UpdateNodeParametersEvent(updateNodeParameters: Signed[UpdateNodeParameters]) extends GlobalSnapshotEvent
}
