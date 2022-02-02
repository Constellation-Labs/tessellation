package org.tessellation.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.tessellation.ext.decline.decline._
import org.tessellation.sdk.config.types.{HttpClientConfig, HttpConfig, HttpServerConfig}

import com.comcast.ip4s.{Host, IpLiteralSyntax, Port}
import com.monovore.decline.Opts

object http {

  val externalIpOpts: Opts[Host] = Opts
    .option[Host]("ip", help = "External IP (a.b.c.d.)")
    .orElse(Opts.env[Host]("CL_EXTERNAL_IP", help = "External IP (a.b.c.d)"))
    .withDefault(host"127.0.0.1")

  val publicHttpPortOpts: Opts[Port] = Opts
    .option[Port]("public-port", help = "Public HTTP port")
    .orElse(Opts.env[Port]("CL_PUBLIC_HTTP_PORT", help = "Public HTTP port"))
    .withDefault(port"9000")

  val p2pHttpPortOpts: Opts[Port] = Opts
    .option[Port]("p2p-port", help = "P2P HTTP port")
    .orElse(Opts.env[Port]("CL_P2P_HTTP_PORT", help = "P2P HTTP port"))
    .withDefault(port"9001")

  val cliHttpPortOpts: Opts[Port] = Opts
    .option[Port]("cli-port", help = "CLI HTTP port")
    .orElse(Opts.env[Port]("CL_CLI_HTTP_PORT", help = "CLI HTTP port"))
    .withDefault(port"9002")

  val client = HttpClientConfig(
    timeout = 60.seconds,
    idleTimeInPool = 30.seconds
  )

  val opts: Opts[HttpConfig] =
    (externalIpOpts, publicHttpPortOpts, p2pHttpPortOpts, cliHttpPortOpts).mapN(
      (externalIp, publicPort, p2pPort, cliPort) =>
        HttpConfig(
          externalIp,
          client,
          HttpServerConfig(host"0.0.0.0", publicPort),
          HttpServerConfig(host"0.0.0.0", p2pPort),
          HttpServerConfig(host"127.0.0.1", cliPort)
        )
    )

}
