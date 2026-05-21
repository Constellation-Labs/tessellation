package io.constellationnetwork.currency.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.cli.http._
import io.constellationnetwork.node.shared.config.types.{HttpConfig, HttpMaxConnectionsDefaults, HttpServerConfig}

import com.comcast.ip4s.IpLiteralSyntax
import com.monovore.decline.Opts

object http {

  val opts: Opts[HttpConfig] =
    (
      externalIpOpts.withDefault(host"127.0.0.1"),
      publicHttpPortOpts.withDefault(port"9200"),
      p2pHttpPortOpts.withDefault(port"9201"),
      cliHttpPortOpts.withDefault(port"9202"),
      publicMaxConnectionsOverrideOpts,
      p2pMaxConnectionsOverrideOpts,
      cliMaxConnectionsOverrideOpts
    ).mapN((externalIp, publicPort, p2pPort, cliPort, publicOverride, p2pOverride, cliOverride) =>
      HttpConfig(
        externalIp,
        client,
        HttpServerConfig(host"0.0.0.0", publicPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"0.0.0.0", p2pPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"127.0.0.1", cliPort, shutdownTimeout = 1.second),
        // Alpha.95: env-aware default ceilings (overlay applied in `HttpConfig.envResolved`).
        publicMaxConnections = HttpMaxConnectionsDefaults.publicHttp,
        p2pMaxConnections = HttpMaxConnectionsDefaults.p2pHttp,
        cliMaxConnections = HttpMaxConnectionsDefaults.cliHttp,
        publicMaxConnectionsOverride = publicOverride,
        p2pMaxConnectionsOverride = p2pOverride,
        cliMaxConnectionsOverride = cliOverride
      )
    )

}
