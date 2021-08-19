package org.tesselation.config

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import org.tesselation.config.AppEnvironment.{Mainnet, Testnet}
import org.tesselation.config.types.{AppConfig, HttpClientConfig}

import ciris._
import com.comcast.ip4s._
import eu.timepit.refined.auto._

object Config {

  def load[F[_]: Async]: F[AppConfig] =
    env("CL_APP_ENV")
      .default("testnet")
      .as[AppEnvironment]
      .flatMap {
        case Testnet => default[F](Testnet)
        case Mainnet => default[F](Mainnet)
      }
      .load[F]

  def default[F[_]](environment: AppEnvironment): ConfigValue[F, AppConfig] =
    (
//      env("CL_DUMMY_SECRET").default("foo").secret,
      env("CL_PUBLIC_HTTP_PORT").default("9000"),
      env("CL_P2P_HTTP_PORT").default("9001"),
      env("CL_HEALTHCHECK_HTTP_PORT").default("9002")
    ).parMapN { (publicHttpPort, p2pHttpPort, healthcheckHttpPort) =>
      AppConfig(
        environment,
        HttpClientConfig(
          timeout = 60.seconds,
          idleTimeInPool = 30.seconds
        ),
        publicHttpPort = Port.fromString(publicHttpPort).get,
        p2pHttpPort = Port.fromString(p2pHttpPort).get,
        healthcheckHttpPort = Port.fromString(healthcheckHttpPort).get
      )
    }

}
