package io.constellationnetwork.dag.l0.domain

import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.security.signature.Signed

object delegatedStake {

  sealed trait DelegatedStakeOutput
  case class CreateDelegatedStakeOutput(value: Signed[UpdateDelegatedStake.Create]) extends DelegatedStakeOutput
  case class WithdrawDelegatedStakeOutput(value: Signed[UpdateDelegatedStake.Withdraw]) extends DelegatedStakeOutput

}
