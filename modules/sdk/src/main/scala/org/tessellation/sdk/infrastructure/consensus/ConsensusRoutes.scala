package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.{Applicative, Order}

import org.tessellation.schema.peer.{PeerId, PeerInfo}
import org.tessellation.sdk.domain.cluster.services.Cluster
import org.tessellation.sdk.infrastructure.consensus.info.ConsensusInfo
import org.tessellation.sdk.infrastructure.consensus.registration.RegistrationResponse

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class ConsensusRoutes[F[_]: Async, Key: Encoder: Order](
  cluster: Cluster[F],
  storage: ConsensusStorage[F, _, Key, _],
  selfId: PeerId
) extends Http4sDsl[F] {

  private val prefixPath = "/consensus"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "registration" =>
      storage.getOwnRegistration.map(RegistrationResponse(_)).flatMap(Ok(_))
  }

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "latest" / "peers" =>
      storage.getLastKey.flatMap {
        case Some(key) => Ok(consensusInfo(key))
        case _         => NotFound()
      }
  }

  private def consensusInfo(latestKey: Key) =
    allRegisteredPeers(latestKey)
      .flatMap(filterClusterPeers)
      .map(ConsensusInfo(latestKey, _))

  private def allRegisteredPeers(latestKey: Key): F[List[PeerId]] =
    storage
      .getRegisteredPeers(latestKey)
      .flatMap { registeredPeers =>
        storage.getOwnRegistration
          .map(_.filter(_ <= latestKey).map(_ => selfId))
          .map(_.toList ::: registeredPeers)
      }

  private def filterClusterPeers(registeredPeers: List[PeerId]): F[Set[PeerInfo]] = registeredPeers match {
    case Nil => Applicative[F].pure(Set.empty[PeerInfo])
    case _   => cluster.info.map(_.filter(p => registeredPeers.contains(p.id)))
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> public
  )

}
