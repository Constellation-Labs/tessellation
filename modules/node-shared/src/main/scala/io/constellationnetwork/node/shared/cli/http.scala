package io.constellationnetwork.node.shared.cli

import scala.concurrent.duration._

import io.constellationnetwork.ext.decline.decline._
import io.constellationnetwork.node.shared.config.types.HttpClientConfig

import com.comcast.ip4s.{Host, Port}
import com.monovore.decline.Opts

object http {
  val externalIpOpts: Opts[Host] = Opts
    .option[Host]("ip", help = "External IP (a.b.c.d)")
    .orElse(Opts.env[Host]("CL_EXTERNAL_IP", help = "External IP (a.b.c.d)"))

  val publicHttpPortOpts: Opts[Port] = Opts
    .option[Port]("public-port", help = "Public HTTP port")
    .orElse(Opts.env[Port]("CL_PUBLIC_HTTP_PORT", help = "Public HTTP port"))

  val p2pHttpPortOpts: Opts[Port] = Opts
    .option[Port]("p2p-port", help = "P2P HTTP port")
    .orElse(Opts.env[Port]("CL_P2P_HTTP_PORT", help = "P2P HTTP port"))

  val cliHttpPortOpts: Opts[Port] = Opts
    .option[Port]("cli-port", help = "CLI HTTP port")
    .orElse(Opts.env[Port]("CL_CLI_HTTP_PORT", help = "CLI HTTP port"))

  val client: HttpClientConfig = HttpClientConfig(
    timeout = 60.seconds,
    idleTimeInPool = 30.seconds
  )

}
