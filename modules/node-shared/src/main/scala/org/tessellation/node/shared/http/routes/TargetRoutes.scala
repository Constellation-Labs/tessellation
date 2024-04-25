package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.node.shared.domain.cluster.services.Cluster
import org.tessellation.node.shared.http.routes.TargetRoutes.Target
import org.tessellation.node.shared.infrastructure.metrics.Metrics.LabelName
import org.tessellation.routes.internal._
import org.tessellation.schema.peer.PeerInfo
import org.tessellation.security.Hasher

import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.refined.{refinedKeyDecoder, refinedKeyEncoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

final case class TargetRoutes[F[_]: Async: Hasher](
  cluster: Cluster[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  protected[routes] val prefixPath: InternalUrlPrefix = "/targets"

  val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => Ok(cluster.info.map(_.map(Target.fromPeerInfo)))
  }
}

object TargetRoutes {

  @derive(decoder, encoder)
  case class Target(targets: List[String], labels: Map[LabelName, String])

  object Target {

    def fromPeerInfo(peer: PeerInfo): Target =
      Target(
        targets = List(s"${peer.ip}:${peer.publicPort}"),
        labels = Map[LabelName, String](
          ("ip", peer.ip.toString),
          ("peer_id", peer.id.value.value),
          ("peer_id_short", peer.id.value.shortValue)
        )
      )
  }

}
