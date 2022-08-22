package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.http4s.SnapshotOrdinalVar
import org.tessellation.modules.{Services, Storages}
import org.tessellation.schema.cluster.SessionAlreadyExists
import org.tessellation.schema.node.InvalidNodeStateTransition
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.PeerDeclaration
import org.tessellation.sdk.infrastructure.consensus.{ConsensusResources, PeerDeclarations}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DebugRoutes[F[_]: Async](
  storages: Storages[F],
  services: Services[F]
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/debug"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root           => Ok()
    case GET -> Root / "peers" => Ok(storages.cluster.getPeers)
    case POST -> Root / "create-session" =>
      services.session.createSession.flatMap(Ok(_)).recoverWith {
        case e: InvalidNodeStateTransition => Conflict(e.getMessage)
        case SessionAlreadyExists          => Conflict(s"Session already exists.")
      }
    case POST -> Root / "gossip" / "spread" / IntVar(intContent) =>
      services.gossip.spread(intContent.some) >> Ok()
    case POST -> Root / "gossip" / "spread" / strContent =>
      services.gossip.spreadCommon(strContent) >> Ok()
    case GET -> Root / "consensus" / SnapshotOrdinalVar(ordinal) / "resources" =>
      services.consensus.storage
        .getResources(ordinal)
        .map(ConsensusResourcesView.fromResources)
        .flatMap(Ok(_))
    case GET -> Root / "consensus" / SnapshotOrdinalVar(ordinal) / "facilitators" =>
      services.consensus.storage.getState(ordinal).map(_.map(_.facilitators)).flatMap {
        _.map(Ok(_)).getOrElse(NotFound())
      }
    case GET -> Root / "consensus" / SnapshotOrdinalVar(ordinal) / "registrations" =>
      services.consensus.storage.getRegisteredPeers(ordinal).flatMap(Ok(_))
  }

  @derive(encoder, decoder)
  case class ConsensusResourcesView(
    facilities: List[PeerId],
    proposals: List[PeerId],
    signatures: List[PeerId]
  )

  object ConsensusResourcesView {
    def fromResources(resources: ConsensusResources[_]): ConsensusResourcesView = {
      def peersWithDeclaration(fn: PeerDeclarations => Option[PeerDeclaration]): List[PeerId] =
        resources.peerDeclarationsMap.toList.mapFilter { case (peerId, pds) => fn(pds).map(_ => peerId) }

      ConsensusResourcesView(
        peersWithDeclaration(_.facility),
        peersWithDeclaration(_.proposal),
        peersWithDeclaration(_.signature)
      )
    }
  }
  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
