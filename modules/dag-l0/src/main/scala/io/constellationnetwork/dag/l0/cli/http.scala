package io.constellationnetwork.dag.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.cli.http._
import io.constellationnetwork.node.shared.config.types.{HttpClientConfig, HttpConfig, HttpServerConfig}

import com.comcast.ip4s._
import com.monovore.decline.Opts

object http {

  val client = HttpClientConfig(
    timeout = 60.seconds,
    idleTimeInPool = 30.seconds
  )

  val opts: Opts[HttpConfig] =
    (
      externalIpOpts.withDefault(host"127.0.0.1"),
      publicHttpPortOpts.withDefault(port"9000"),
      p2pHttpPortOpts.withDefault(port"9001"),
      cliHttpPortOpts.withDefault(port"9002")
    ).mapN((externalIp, publicPort, p2pPort, cliPort) =>
      HttpConfig(
        externalIp,
        client,
        HttpServerConfig(host"0.0.0.0", publicPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"0.0.0.0", p2pPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"127.0.0.1", cliPort, shutdownTimeout = 1.second)
      )
    )

}
