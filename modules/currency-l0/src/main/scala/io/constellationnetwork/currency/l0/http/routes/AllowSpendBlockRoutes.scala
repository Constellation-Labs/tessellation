package io.constellationnetwork.currency.l0.http.routes

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.snapshot.currency.{AllowSpendBlockEvent, CurrencySnapshotEvent}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

final case class AllowSpendBlockRoutes[F[_]: Async](
  queue: Queue[F, CurrencySnapshotEvent]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-allow-spend-output" =>
      req
        .as[Signed[AllowSpendBlock]]
        .map(AllowSpendBlockEvent(_))
        .flatMap(queue.offer)
        .flatMap(_ => Ok())
  }
}
