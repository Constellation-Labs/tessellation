package io.constellationnetwork.currency.l0.http.routes

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotEvent, TokenLockBlockEvent}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

final case class TokenLockBlockRoutes[F[_]: Async](
  queue: Queue[F, CurrencySnapshotEvent]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-token-lock-output" =>
      req
        .as[Signed[TokenLockBlock]]
        .map(TokenLockBlockEvent(_))
        .flatMap(queue.offer)
        .flatMap(_ => Ok())
  }
}
