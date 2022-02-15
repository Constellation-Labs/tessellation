package org.tessellation.l0.http.routes

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.security.signature.Signed

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class BlockRoutes[F[_]: Async](
  l1BlocksQueue: Queue[F, Set[Signed[DAGBlock]]]
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/block"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req
        .as[Signed[DAGBlock]]
        .map(Set(_))
        .flatMap(l1BlocksQueue.offer)
        .flatMap(_ => Ok())
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

}
