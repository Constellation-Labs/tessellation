package io.constellationnetwork.currency.l0.snapshot.synchronous

import cats.Monad
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.CurrencyConsensusStorage
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusHealthStatus
import io.constellationnetwork.routes.internal.{InternalUrlPrefix, PublicRoutes}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.SessionAlreadyExists
import io.constellationnetwork.schema.node.InvalidNodeStateTransition
import io.constellationnetwork.schema.peer.{PeerId, PeerInfo}
import io.constellationnetwork.security.HasherSelector

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

/** Currency-local replacements for the public/debug routes that are typed to the shared GL0 engine.
  *
  * Keeping these routes beside the stable synchronous Currency engine avoids widening the shared consensus API merely for diagnostics.
  */
object CurrencyConsensusHttpRoutes {

  final class Info[F[_]: Async: HasherSelector](
    cluster: Cluster[F],
    storage: CurrencyConsensusStorage[F],
    selfId: PeerId
  ) extends Http4sDsl[F]
      with PublicRoutes[F] {

    protected val prefixPath: InternalUrlPrefix = "/consensus"

    protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "latest" / "peers" =>
        storage.getLastConsensusOutcome.flatMap {
          case Some(outcome) =>
            HasherSelector[F].withCurrent { implicit hasher =>
              cluster.info
                .map(_.filter(peer => outcome.facilitators.value.toSet.incl(selfId).contains(peer.id)))
                .map(peers => ConsensusInfo(outcome.key, peers))
                .flatMap(Ok(_))
            }
          case None => NotFound()
        }

      // The stable Currency protocol has no GL0 health-controller state.
      case GET -> Root / "health" => Ok(ConsensusHealthStatus.empty)
    }
  }

  final class Debug[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    storage: CurrencyConsensusStorage[F],
    gossip: Gossip[F],
    session: Session[F]
  ) extends Http4sDsl[F]
      with PublicRoutes[F] {

    protected val prefixPath: InternalUrlPrefix = "/debug"

    protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root           => Ok()
      case GET -> Root / "peers" => Ok(clusterStorage.getPeers)
      case POST -> Root / "create-session" =>
        session.createSession.flatMap(Ok(_)).recoverWith {
          case e: InvalidNodeStateTransition => Conflict(e.getMessage)
          case SessionAlreadyExists          => Conflict("Session already exists.")
        }
      case POST -> Root / "gossip" / "spread" / IntVar(content) => gossip.spread(content.some) >> Ok()
      case POST -> Root / "gossip" / "spread" / content         => gossip.spreadCommon(content) >> Ok()
      case GET -> Root / "consensus" / LongVar(ordinal) / "resources" =>
        storage
          .getResources(SnapshotOrdinal.unsafeApply(ordinal))
          .map(ResourcesView.fromResources(_))
          .flatMap(Ok(_))
      case GET -> Root / "consensus" / LongVar(ordinal) / "facilitators" =>
        storage.getState(SnapshotOrdinal.unsafeApply(ordinal)).flatMap {
          _.map(state => Ok(state.facilitators)).getOrElse(NotFound())
        }
      case GET -> Root / "consensus" / LongVar(ordinal) / "candidates" =>
        storage.getCandidates(SnapshotOrdinal.unsafeApply(ordinal)).flatMap(Ok(_))
    }

    override def publicRoutes(implicit monad: Monad[F]): HttpRoutes[F] = Router(prefixPath.value -> public)
  }

  @derive(encoder)
  final case class ConsensusInfo(key: SnapshotOrdinal, peers: Set[PeerInfo])

  @derive(encoder, decoder)
  final case class ResourcesView(
    facilities: List[PeerId],
    proposals: List[PeerId],
    signatures: List[PeerId],
    binarySignatures: List[PeerId]
  )

  object ResourcesView {
    def fromResources(resources: ConsensusResources[_, _]): ResourcesView = {
      def peersWith(get: PeerDeclarations => Option[_]): List[PeerId] =
        resources.peerDeclarationsMap.toList.collect { case (peerId, declarations) if get(declarations).isDefined => peerId }

      ResourcesView(
        peersWith(_.facility),
        peersWith(_.proposal),
        peersWith(_.signature),
        peersWith(_.binarySignature)
      )
    }
  }
}
