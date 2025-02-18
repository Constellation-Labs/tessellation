package io.constellationnetwork.dag.l0.domain

import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.security.signature.Signed

object nodeCollateral {

  sealed trait NodeCollateralOutput
  case class CreateNodeCollateralOutput(value: Signed[UpdateNodeCollateral.Create]) extends NodeCollateralOutput
  case class WithdrawNodeCollateralOutput(value: Signed[UpdateNodeCollateral.Withdraw]) extends NodeCollateralOutput

}
