package io.constellationnetwork.dag.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.cli.http._
import io.constellationnetwork.node.shared.config.types._

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
      cliHttpPortOpts.withDefault(port"9002"),
      publicMaxConnectionsOverrideOpts,
      p2pMaxConnectionsOverrideOpts,
      cliMaxConnectionsOverrideOpts
    ).mapN((externalIp, publicPort, p2pPort, cliPort, publicOverride, p2pOverride, cliOverride) =>
      HttpConfig(
        externalIp,
        client,
        // Listener field defaults stay at `HttpServerConfig.maxConnections = PosInt(100)`.
        // `HttpConfig.envResolved(env)` then overlays (in order): operator override -> compiled
        // env-default -> field default.
        HttpServerConfig(host"0.0.0.0", publicPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"0.0.0.0", p2pPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"127.0.0.1", cliPort, shutdownTimeout = 1.second),
        publicMaxConnections = HttpMaxConnectionsDefaults.publicHttp,
        p2pMaxConnections = HttpMaxConnectionsDefaults.p2pHttp,
        cliMaxConnections = HttpMaxConnectionsDefaults.cliHttp,
        publicMaxConnectionsOverride = publicOverride,
        p2pMaxConnectionsOverride = p2pOverride,
        cliMaxConnectionsOverride = cliOverride
      )
    )

}
