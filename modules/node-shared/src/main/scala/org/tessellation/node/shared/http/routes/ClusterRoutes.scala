package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.node.shared.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.node.shared.domain.cluster.services.Cluster
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.collateral.Collateral
import org.tessellation.node.shared.ext.http4s.refined.RefinedRequestDecoder
import org.tessellation.routes.internal._
import org.tessellation.schema.cluster._
import org.tessellation.schema.peer.JoinRequest

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class ClusterRoutes[F[_]: Async](
  joining: Joining[F],
  peerDiscovery: PeerDiscovery[F],
  clusterStorage: ClusterStorage[F],
  cluster: Cluster[F],
  collateral: Collateral[F]
) extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F]
    with P2PPublicRoutes[F]
    with CliRoutes[F] {

  implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  protected[routes] val prefixPath: InternalUrlPrefix = "/cluster"

  protected val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "join" =>
      req.decodeR[PeerToJoin] { peerToJoin =>
        joining
          .join(peerToJoin)
          .flatMap(_ => Ok())
          .recoverWith {
            case NodeStateDoesNotAllowForJoining(nodeState) =>
              Conflict(s"Node state=${nodeState} does not allow for joining the cluster.")
            case PeerAlreadyJoinedWithNewerSession(id, _, _, _) => Conflict(s"Peer id=${id.show} already joined with newer session.")
            case SessionAlreadyExists =>
              Conflict(s"Session already exists.")
            case _ =>
              InternalServerError("Unknown error.")
          }
      }
    case POST -> Root / "leave" =>
      cluster.leave() >> Ok()
  }

  protected val p2pPublic: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "join" =>
      req.decodeR[JoinRequest] { joinRequest =>
        joining
          .joinRequest(collateral.hasCollateral)(joinRequest, joinRequest.registrationRequest.ip)
          .flatMap(_ => Ok())
          .recoverWith {
            case PeerAlreadyJoinedWithNewerSession(id, _, _, _) => Conflict(s"Peer id=${id.show} already joined with newer session.")
            case PeerAlreadyJoinedWithDifferentRegistrationData(id) =>
              Conflict(s"Peer id=${id.show} already joined with different registration data.")
            case SessionDoesNotExist    => Conflict("Peer does not have an active session.")
            case CollateralNotSatisfied => Conflict("Collateral is not satisfied.")
            case NodeNotInCluster       => Conflict("Node is not part of the cluster.")
            case _                      => InternalServerError("Unknown error.")
          }
      }
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "peers" =>
      Ok(clusterStorage.getPeers)
    case GET -> Root / "discovery" =>
      Ok(
        clusterStorage.getResponsivePeers.flatMap { knownPeers =>
          peerDiscovery.getPeers.map { discoveredPeers =>
            knownPeers ++ discoveredPeers
          }
        }
      )
  }

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "info" =>
      Ok(cluster.info)
    case GET -> Root / "session" =>
      clusterStorage.getToken.flatMap {
        case Some(token) => Ok(("token" ->> token) :: HNil)
        case None        => NotFound()
      }
  }
}
