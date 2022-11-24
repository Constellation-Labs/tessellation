package org.tessellation.currency.cli

import cats.syntax.contravariantSemigroupal._

import org.tessellation.sdk.cli.http._
import org.tessellation.sdk.config.types.{HttpConfig, HttpServerConfig}

import com.comcast.ip4s.IpLiteralSyntax
import com.monovore.decline.Opts

object http {

  val opts: Opts[HttpConfig] =
    (
      externalIpOpts,
      publicHttpPortOpts,
      p2pHttpPortOpts,
      cliHttpPortOpts
    ).mapN((externalIp, publicPort, p2pPort, cliPort) =>
      HttpConfig(
        externalIp,
        client,
        HttpServerConfig(host"0.0.0.0", publicPort),
        HttpServerConfig(host"0.0.0.0", p2pPort),
        HttpServerConfig(host"127.0.0.1", cliPort)
      )
    )

}
