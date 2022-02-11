package org.tessellation.routes

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._

import org.tessellation.dag.domain.block.L1Output

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class BlockRoutes[F[_]: Async](l1OutputQueue: Queue[F, L1Output]) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/block"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req
        .as[L1Output]
        .flatMap(l1OutputQueue.offer)
        .flatMap(_ => Ok())
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

}
