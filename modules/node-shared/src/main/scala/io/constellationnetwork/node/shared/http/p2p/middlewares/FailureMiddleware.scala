package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.implicits._

import scala.concurrent.duration._

import org.http4s.HttpRoutes

object FailureMiddleware {

  def failureSimulatorFeatureCheck[F[_]: Async](envVarName: String): F[Unit] =
    Async[F].delay {
      sys.env.get(envVarName).map(_.toLong)
    }.flatMap {
      case Some(failTime) =>
        // Monotonic is off by about 30 seconds here, ideal to switch to in future.
        // Clock[F].monotonic.flatMap { monotonic =>
        Clock[F].realTime.flatMap { currentTime =>
          val currentTimeSeconds = currentTime.toSeconds
          if (currentTimeSeconds > failTime) {
            Async[F].sleep(300.seconds)
          } else {
            Async[F].unit
          }
        }
      // }
      case None =>
        Async[F].unit
    }

  def withFailureSimulator[F[_]: Async](envVarName: String): HttpRoutes[F] => HttpRoutes[F] = { routes =>
    Kleisli { req =>
      OptionT.liftF(failureSimulatorFeatureCheck[F](envVarName)).flatMap { _ =>
        routes(req)
      }
    }
  }
}
