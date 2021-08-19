package org.tesselation.config

import cats.effect.Async

import scala.concurrent.duration._

import org.tesselation.config.AppEnvironment.{Mainnet, Testnet}
import org.tesselation.config.types.{AppConfig, HttpClientConfig, HttpServerConfig}

import ciris._
import com.comcast.ip4s._
import eu.timepit.refined.auto._

object Config {

  def load[F[_]: Async]: F[AppConfig] =
    env("CL_APP_ENV")
//      .default("testnet")
      .as[AppEnvironment]
      .flatMap {
        case Testnet => default[F](Testnet)
        case Mainnet => default[F](Mainnet)
      }
      .load[F]

  def default[F[_]](environment: AppEnvironment): ConfigValue[F, AppConfig] =
    env("CL_DUMMY_SECRET").default("foo").secret.map { _ =>
      AppConfig(
        environment,
        HttpClientConfig(
          timeout = 60.seconds,
          idleTimeInPool = 30.seconds
        ),
        HttpServerConfig(
          host = host"0.0.0.0",
          port = port"9000"
        )
      )
    }

}
