package io.constellationnetwork.dag.l0.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.cli.http._
import io.constellationnetwork.node.shared.config.types.{HttpClientConfig, HttpConfig, HttpServerConfig}

import com.comcast.ip4s._
import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument
import eu.timepit.refined.types.numeric.PosInt

object http {

  val client = HttpClientConfig(
    timeout = 60.seconds,
    idleTimeInPool = 30.seconds
  )

  // Public listener cap default. Backstops the per-route ConcurrencyLimitMiddleware in PR-1:
  // a buggy or hostile client cannot exhaust handler threads or fds regardless of which route
  // they hit. Override per environment via --public-max-connections / CL_PUBLIC_HTTP_MAX_CONNECTIONS
  // if a deployment needs a different ceiling.
  val publicMaxConnectionsDefault: PosInt = PosInt(100)

  val publicMaxConnectionsOpts: Opts[PosInt] = Opts
    .option[PosInt]("public-max-connections", help = "Max concurrent connections on public HTTP server")
    .orElse(Opts.env[PosInt]("CL_PUBLIC_HTTP_MAX_CONNECTIONS", help = "Max concurrent connections on public HTTP server"))
    .withDefault(publicMaxConnectionsDefault)

  val opts: Opts[HttpConfig] =
    (
      externalIpOpts.withDefault(host"127.0.0.1"),
      publicHttpPortOpts.withDefault(port"9000"),
      p2pHttpPortOpts.withDefault(port"9001"),
      cliHttpPortOpts.withDefault(port"9002"),
      publicMaxConnectionsOpts
    ).mapN((externalIp, publicPort, p2pPort, cliPort, publicMaxConn) =>
      HttpConfig(
        externalIp,
        client,
        HttpServerConfig(host"0.0.0.0", publicPort, shutdownTimeout = 1.second, maxConnections = publicMaxConn),
        HttpServerConfig(host"0.0.0.0", p2pPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"127.0.0.1", cliPort, shutdownTimeout = 1.second)
      )
    )

}
