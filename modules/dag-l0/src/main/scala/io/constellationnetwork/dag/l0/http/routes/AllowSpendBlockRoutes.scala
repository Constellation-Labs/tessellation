package io.constellationnetwork.dag.l0.http.routes

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

final case class AllowSpendBlockRoutes[F[_]: Async](
  queue: Queue[F, Signed[AllowSpendBlock]]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  protected val prefixPath: InternalUrlPrefix = "/dag"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-allow-spend-output" =>
      req
        .as[Signed[AllowSpendBlock]]
        .flatMap(queue.offer)
        .flatMap(_ => Ok())
  }
}
