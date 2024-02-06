package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.routes.internal._
import org.tessellation.schema.node.NodeInfo
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.TessellationVersion

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

final case class NodeRoutes[F[_]: Async](
  nodeStorage: NodeStorage[F],
  sessionStorage: SessionStorage[F],
  clusterStorage: ClusterStorage[F],
  version: TessellationVersion,
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
        NodeInfo(
          state,
          session,
          clusterSession,
          version.version.value,
          httpCfg.externalIp,
          httpCfg.publicHttp.port,
          httpCfg.p2pHttp.port,
          selfId
        )
      }.flatMap(Ok(_))

    case GET -> Root / "state" =>
      Ok(nodeStorage.getNodeState)

    case GET -> Root / "health" =>
      Ok()
  }
}
