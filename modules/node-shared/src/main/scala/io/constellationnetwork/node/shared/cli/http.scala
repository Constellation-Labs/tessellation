package io.constellationnetwork.node.shared.cli

import scala.concurrent.duration._

import io.constellationnetwork.ext.decline.decline._
import io.constellationnetwork.node.shared.config.types.HttpClientConfig

import com.comcast.ip4s.{Host, Port}
import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument
import eu.timepit.refined.types.numeric.PosInt

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

  // Alpha.95: per-listener Ember `maxConnections` overrides. Each returns `Option[PosInt]`
  // via `.orNone` so the absence of an explicit operator setting is distinguishable from
  // the underlying field default. `HttpConfig.envResolved(env)` consumes these as the
  // highest-precedence input (operator wins over compiled per-env defaults).
  //
  // The pre-alpha.95 dag-l0 `--public-max-connections` knob is preserved (same name, same
  // env var) and now also works for dag-l1 / currency-l0 / currency-l1 since the opts are
  // shared here. The two new `--p2p-max-connections` / `--cli-max-connections` knobs were
  // added because PR-1's blanket `maxConnections = 100` default on the p2p socket caused
  // the May 17 testnet chain-growth regression (commit `2cbff6aee`), and operators need a
  // tuning knob beyond recompiling the env-default map.
  val publicMaxConnectionsOverrideOpts: Opts[Option[PosInt]] = Opts
    .option[PosInt]("public-max-connections", help = "Max concurrent connections on public HTTP server (operator override)")
    .orElse(Opts.env[PosInt]("CL_PUBLIC_HTTP_MAX_CONNECTIONS", help = "Max concurrent connections on public HTTP server"))
    .orNone

  val p2pMaxConnectionsOverrideOpts: Opts[Option[PosInt]] = Opts
    .option[PosInt]("p2p-max-connections", help = "Max concurrent connections on p2p HTTP server (operator override)")
    .orElse(Opts.env[PosInt]("CL_P2P_HTTP_MAX_CONNECTIONS", help = "Max concurrent connections on p2p HTTP server"))
    .orNone

  val cliMaxConnectionsOverrideOpts: Opts[Option[PosInt]] = Opts
    .option[PosInt]("cli-max-connections", help = "Max concurrent connections on cli HTTP server (operator override)")
    .orElse(Opts.env[PosInt]("CL_CLI_HTTP_MAX_CONNECTIONS", help = "Max concurrent connections on cli HTTP server"))
    .orNone

}
