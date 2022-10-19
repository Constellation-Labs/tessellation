package org.tessellation.sdk.cli

import org.tessellation.schema.balance.Amount

import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument
import eu.timepit.refined.types.numeric.NonNegLong

object CollateralAmountOpts {

  val opts: Opts[Option[Amount]] = Opts
    .option[NonNegLong]("collateral", help = "Minimum staking amount to run a node")
    .orElse(Opts.env[NonNegLong]("CL_COLLATERAL", help = "Minimum staking amount to run a node"))
    .map(Amount(_))
    .orNone
}
