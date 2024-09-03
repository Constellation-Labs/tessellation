package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.cluster.services.Cluster
import io.constellationnetwork.node.shared.http.routes.ConsensusInfoRoutes.ConsensusInfo
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusStorage, Facilitators}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.peer.{PeerId, PeerInfo}
import io.constellationnetwork.security.Hasher

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.Encoder
import monocle.Lens
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

class ConsensusInfoRoutes[F[_]: Async: Hasher, Key: Encoder, Outcome](
  cluster: Cluster[F],
  consensusStorage: ConsensusStorage[F, _, Key, _, _, _, Outcome, _],
  selfId: PeerId
)(implicit _facilitators: Lens[Outcome, Facilitators], _key: Lens[Outcome, Key])
    extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/consensus"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "latest" / "peers" =>
      consensusStorage.getLastConsensusOutcome.flatMap {
        case Some(outcome) => Ok(makeConsensusInfo(outcome))
        case _             => NotFound()
      }
  }

  private def makeConsensusInfo(outcome: Outcome): F[ConsensusInfo[Key]] =
    filterClusterPeers(_facilitators.get(outcome).value.toSet.incl(selfId))
      .map(ConsensusInfo(_key.get(outcome), _))

  private def filterClusterPeers(peers: Set[PeerId]): F[Set[PeerInfo]] =
    cluster.info.map(_.filter(peerInfo => peers.contains(peerInfo.id)))

}

object ConsensusInfoRoutes {
  @derive(encoder)
  case class ConsensusInfo[Key](
    key: Key,
    peers: Set[PeerInfo]
  )
}
