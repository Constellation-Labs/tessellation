package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import org.tessellation.http.routes.internal.{InternalUrlPrefix, P2PRoutes, PublicRoutes}
import org.tessellation.schema.node.NodeInfo
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HttpConfig
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.node.NodeStorage

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

final case class NodeRoutes[F[_]: Async](
  nodeStorage: NodeStorage[F],
  sessionStorage: SessionStorage[F],
  clusterStorage: ClusterStorage[F],
  version: String,
  httpCfg: HttpConfig,
  selfId: PeerId
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {
  protected[routes] val prefixPath: InternalUrlPrefix = "/node"

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "state" =>
      Ok(nodeStorage.getNodeState)

    case GET -> Root / "health" =>
      Ok()

    case GET -> Root / "session" =>
      Ok(sessionStorage.getToken)
  }

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "info" =>
      (nodeStorage.getNodeState, sessionStorage.getToken, clusterStorage.getToken).mapN { (state, session, clusterSession) =>
        NodeInfo(state, session, clusterSession, version, httpCfg.externalIp, httpCfg.publicHttp.port, httpCfg.p2pHttp.port, selfId)
      }.flatMap(Ok(_))

    case GET -> Root / "state" =>
      Ok(nodeStorage.getNodeState)

    case GET -> Root / "health" =>
      Ok()
  }
}
