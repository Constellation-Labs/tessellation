package io.constellationnetwork.dag.l0.infrastructure.snapshot

import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
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

  @derive(eqv, decoder, encoder, show)
  case class UpdateNodeParametersEvent(updateNodeParameters: Signed[UpdateNodeParameters]) extends GlobalSnapshotEvent

  @derive(eqv, decoder, encoder, show)
  sealed trait UpdateDelegatedStakeEvent extends GlobalSnapshotEvent
  @derive(eqv, decoder, encoder, show)
  case class CreateDelegatedStakeEvent(value: Signed[UpdateDelegatedStake.Create]) extends UpdateDelegatedStakeEvent
  @derive(eqv, decoder, encoder, show)
  case class WithdrawDelegatedStakeEvent(value: Signed[UpdateDelegatedStake.Withdraw]) extends UpdateDelegatedStakeEvent

  @derive(eqv, decoder, encoder, show)
  sealed trait UpdateNodeCollateralEvent extends GlobalSnapshotEvent
  @derive(eqv, decoder, encoder, show)
  case class CreateNodeCollateralEvent(value: Signed[UpdateNodeCollateral.Create]) extends UpdateNodeCollateralEvent
  @derive(eqv, decoder, encoder, show)
  case class WithdrawNodeCollateralEvent(value: Signed[UpdateNodeCollateral.Withdraw]) extends UpdateNodeCollateralEvent
}
