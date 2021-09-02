package org.tesselation.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.tesselation.domain.cluster.{Cluster, ClusterStorage}
import org.tesselation.ext.http4s.refined._
import org.tesselation.schema.cluster._

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class ClusterRoutes[F[_]: Async](
  clusterStorage: ClusterStorage[F],
  cluster: Cluster[F]
) extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private[routes] val prefixPath = "/cluster"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "peers" =>
      Ok(clusterStorage.getPeers)

    case req @ POST -> Root / "join" =>
      req.decodeR[PeerToJoin] { peerToJoin =>
        cluster
          .join(peerToJoin)
          .flatMap(_ => Ok())
          .recoverWith {
            case NodeStateDoesNotAllowForJoining(nodeState) =>
              Conflict(s"Node state=${nodeState} does not allow for joining the cluster.")
            case PeerIdInUse(id) => Conflict(s"Peer id=${id} already in use.")
            case PeerHostPortInUse(host, port) =>
              Conflict(s"Peer host=${host.toString} port=${port.value} already in use.")
          }
      }
  }

  val cliRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
