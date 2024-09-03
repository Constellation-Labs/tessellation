package io.constellationnetwork.currency.cli

import io.constellationnetwork.schema.address.{Address, DAGAddress}

import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument

object L0TokenIdentifierOpts {

  val opts: Opts[Address] = Opts
    .option[DAGAddress]("l0-token-identifier", help = "L0 token identifier address")
    .orElse(Opts.env[DAGAddress]("CL_L0_TOKEN_IDENTIFIER", help = "L0 token identifier address"))
    .map(Address(_))
}
