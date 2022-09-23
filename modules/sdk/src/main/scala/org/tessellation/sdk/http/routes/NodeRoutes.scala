package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import org.tessellation.schema.node.NodeInfo
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HttpConfig
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.node.NodeStorage

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class NodeRoutes[F[_]: Async](
  nodeStorage: NodeStorage[F],
  sessionStorage: SessionStorage[F],
  clusterStorage: ClusterStorage[F],
  version: String,
  httpCfg: HttpConfig,
  selfId: PeerId
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
      (nodeStorage.getNodeState, sessionStorage.getToken, clusterStorage.getToken).mapN { (state, session, clusterSession) =>
        NodeInfo(state, session, clusterSession, version, httpCfg.externalIp, httpCfg.publicHttp.port, httpCfg.p2pHttp.port, selfId)
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
