package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.routes.internal._
import org.tessellation.schema.node.MetagraphNodeInfo
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

final case class MetagraphRoutes[F[_]: Async](
  nodeStorage: NodeStorage[F],
  sessionStorage: SessionStorage[F],
  clusterStorage: ClusterStorage[F],
  httpCfg: HttpConfig,
  selfId: PeerId,
  tessellationVersion: String,
  metagraphVersion: String
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {
  protected[routes] val prefixPath: InternalUrlPrefix = "/metagraph"

  protected val p2p: HttpRoutes[F] = HttpRoutes.empty

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "info" =>
      (nodeStorage.getNodeState, sessionStorage.getToken, clusterStorage.getToken).mapN { (state, session, clusterSession) =>
        MetagraphNodeInfo(
          state,
          session,
          clusterSession,
          httpCfg.externalIp,
          httpCfg.publicHttp.port,
          httpCfg.p2pHttp.port,
          selfId,
          tessellationVersion,
          metagraphVersion
        )
      }.flatMap(Ok(_))
  }
}
