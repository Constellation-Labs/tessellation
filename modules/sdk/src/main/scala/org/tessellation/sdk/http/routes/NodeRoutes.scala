package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import org.tessellation.sdk.domain.cluster.storage.SessionStorage
import org.tessellation.sdk.domain.node.NodeStorage

import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import shapeless._
import shapeless.syntax.singleton._

final case class NodeRoutes[F[_]: Async](
  nodeStorage: NodeStorage[F],
  sessionStorage: SessionStorage[F],
  version: String
) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/node"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "state" =>
      Ok(nodeStorage.getNodeState)

    case GET -> Root / "health" =>
      Ok()

    case GET -> Root / "session" =>
      Ok(sessionStorage.getToken)
  }

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "info" =>
      (nodeStorage.getNodeState, sessionStorage.getToken).mapN { (state, session) =>
        ("state" ->> state) :: ("session" ->> session) :: ("version" ->> version) :: HNil
      }.flatMap(Ok(_))

    case GET -> Root / "state" =>
      Ok(nodeStorage.getNodeState)

    case GET -> Root / "health" =>
      Ok()
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> public
  )
}
