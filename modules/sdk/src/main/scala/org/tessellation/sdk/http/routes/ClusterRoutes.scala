package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.schema.cluster._
import org.tessellation.schema.peer.JoinRequest
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.sdk.domain.cluster.services.Cluster
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.ext.http4s.refined.RefinedRequestDecoder

import com.comcast.ip4s.Host
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class ClusterRoutes[F[_]: Async](
  joining: Joining[F],
  peerDiscovery: PeerDiscovery[F],
  clusterStorage: ClusterStorage[F],
  cluster: Cluster[F],
  collateral: Collateral[F]
) extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private[routes] val prefixPath = "/cluster"

  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "join" =>
      req.decodeR[PeerToJoin] { peerToJoin =>
        joining
          .join(peerToJoin)
          .flatMap(_ => Ok())
          .recoverWith {
            case NodeStateDoesNotAllowForJoining(nodeState) =>
              Conflict(s"Node state=${nodeState} does not allow for joining the cluster.")
            case PeerIdInUse(id) => Conflict(s"Peer id=${id} already in use.")
            case PeerHostPortInUse(host, port) =>
              Conflict(s"Peer host=${host.toString} port=${port.value} already in use.")
            case SessionAlreadyExists =>
              Conflict(s"Session already exists.")
            case _ =>
              InternalServerError("Unknown error.")
          }
      }
    case POST -> Root / "leave" =>
      cluster.leave() >> Ok()
  }

  private val p2pPublic: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "join" =>
      req.decodeR[JoinRequest] { joinRequest =>
        req.remoteAddr
          .flatMap(_.asIpv4)
          .map(_.toString)
          .flatMap(Host.fromString)
          .fold(BadRequest())(host =>
            joining
              .joinRequest(collateral.hasCollateral)(joinRequest, host)
              .flatMap(_ => Ok())
              .recoverWith {
                case PeerIdInUse(id)               => Conflict(s"Peer id=${id} already in use.")
                case PeerHostPortInUse(host, port) => Conflict(s"Peer host=${host.toString} port=${port.value} already in use.")
                case SessionDoesNotExist           => Conflict("Peer does not have an active session.")
                case CollateralNotSatisfied        => Conflict("Collateral is not satisfied.")
                case NodeNotInCluster              => Conflict("Node is not part of the cluster.")
                case _                             => InternalServerError("Unknown error.")
              }
          )

      }
  }

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "peers" =>
      Ok(clusterStorage.getPeers)
    case GET -> Root / "discovery" =>
      Ok(
        clusterStorage.getPeers
          .map(_.map(toP2PContext))
          .flatMap { knownPeers =>
            peerDiscovery.getPeers.map { discoveredPeers =>
              knownPeers ++ discoveredPeers
            }
          }
      )
  }

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "info" =>
      Ok(cluster.info)
    case GET -> Root / "session" =>
      clusterStorage.getToken.flatMap {
        case Some(token) => Ok(("token" ->> token) :: HNil)
        case None        => NotFound()
      }
  }

  val p2pPublicRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2pPublic
  )

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

  val cliRoutes: HttpRoutes[F] = Router(
    prefixPath -> cli
  )

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> public
  )
}
