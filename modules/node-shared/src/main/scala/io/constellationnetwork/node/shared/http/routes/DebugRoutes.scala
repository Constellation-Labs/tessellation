package io.constellationnetwork.node.shared.http.routes

import cats.Monad
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.ext.http4s.SnapshotOrdinalVar
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.PeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensus
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.cluster.SessionAlreadyExists
import io.constellationnetwork.schema.node.InvalidNodeStateTransition
import io.constellationnetwork.schema.peer.PeerId

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DebugRoutes[F[_]: Async](
  clusterStorage: ClusterStorage[F],
  consensusService: SnapshotConsensus[F, _, _, _, _, _, _],
  gossipService: Gossip[F],
  sessionService: Session[F],
  additionalRoutes: HttpRoutes[F]*
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected[routes] val prefixPath: InternalUrlPrefix = "/debug"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root           => Ok()
    case GET -> Root / "peers" => Ok(clusterStorage.getPeers)
    case POST -> Root / "create-session" =>
      sessionService.createSession.flatMap(Ok(_)).recoverWith {
        case e: InvalidNodeStateTransition => Conflict(e.getMessage)
        case SessionAlreadyExists          => Conflict(s"Session already exists.")
      }
    case POST -> Root / "gossip" / "spread" / IntVar(intContent) =>
      gossipService.spread(intContent.some) >> Ok()
    case POST -> Root / "gossip" / "spread" / strContent =>
      gossipService.spreadCommon(strContent) >> Ok()
    case GET -> Root / "consensus" / SnapshotOrdinalVar(ordinal) / "resources" =>
      consensusService.storage
        .getResources(ordinal)
        .map(ConsensusResourcesView.fromResources)
        .flatMap(Ok(_))
    case GET -> Root / "consensus" / SnapshotOrdinalVar(ordinal) / "facilitators" =>
      consensusService.storage.getState(ordinal).map(_.map(_.facilitators)).flatMap {
        _.map(Ok(_)).getOrElse(NotFound())
      }
    case GET -> Root / "consensus" / SnapshotOrdinalVar(ordinal) / "candidates" =>
      consensusService.storage.getCandidates(ordinal).flatMap(Ok(_))
  }

  @derive(encoder, decoder)
  case class ConsensusResourcesView(
    facilities: List[PeerId],
    proposals: List[PeerId],
    signatures: List[PeerId]
  )

  object ConsensusResourcesView {
    def fromResources(resources: ConsensusResources[_, _]): ConsensusResourcesView = {
      def peersWithDeclaration(fn: PeerDeclarations => Option[PeerDeclaration]): List[PeerId] =
        resources.peerDeclarationsMap.toList.mapFilter { case (peerId, pds) => fn(pds).map(_ => peerId) }

      ConsensusResourcesView(
        peersWithDeclaration(_.facility),
        peersWithDeclaration(_.proposal),
        peersWithDeclaration(_.signature)
      )
    }
  }

  override def publicRoutes(implicit m: Monad[F]): HttpRoutes[F] = Router(
    prefixPath.value -> additionalRoutes.fold(public)(_ <+> _)
  )
}
