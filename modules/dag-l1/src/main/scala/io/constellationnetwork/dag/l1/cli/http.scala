package io.constellationnetwork.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.cli.http._
import io.constellationnetwork.node.shared.config.types.{HttpClientConfig, HttpConfig, HttpServerConfig}
import io.constellationnetwork.node.shared.infrastructure._

import com.comcast.ip4s.IpLiteralSyntax
import com.monovore.decline.Opts

object http {

  val client = HttpClientConfig(
    timeout = 60.seconds,
    idleTimeInPool = 30.seconds
  )

  def opts(l1Layers: L1Layer): Opts[HttpConfig] =
    (l1Layers match {
      case DagL1 =>
        (
          externalIpOpts.withDefault(host"127.0.0.1"),
          publicHttpPortOpts.withDefault(port"9100"),
          p2pHttpPortOpts.withDefault(port"9101"),
          cliHttpPortOpts.withDefault(port"9102")
        )
      case CurrencyL1 =>
        (
          externalIpOpts.withDefault(host"127.0.0.1"),
          publicHttpPortOpts.withDefault(port"9300"),
          p2pHttpPortOpts.withDefault(port"9301"),
          cliHttpPortOpts.withDefault(port"9302")
        )
      case DataL1 =>
        (
          externalIpOpts.withDefault(host"127.0.0.1"),
          publicHttpPortOpts.withDefault(port"9400"),
          p2pHttpPortOpts.withDefault(port"9401"),
          cliHttpPortOpts.withDefault(port"9402")
        )
    }).mapN((externalIp, publicPort, p2pPort, cliPort) =>
      HttpConfig(
        externalIp,
        client,
        HttpServerConfig(host"0.0.0.0", publicPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"0.0.0.0", p2pPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"127.0.0.1", cliPort, shutdownTimeout = 1.second)
      )
    )

}
