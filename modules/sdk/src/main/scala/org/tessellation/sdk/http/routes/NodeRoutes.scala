package org.tessellation.sdk.http.routes

import cats.effect.Async

import org.tessellation.sdk.domain.node.NodeStorage

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class NodeRoutes[F[_]: Async](
  nodeStorage: NodeStorage[F]
) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/node"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "state" =>
      Ok(nodeStorage.state)

    case GET -> Root / "health" =>
      Ok()
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )
}
