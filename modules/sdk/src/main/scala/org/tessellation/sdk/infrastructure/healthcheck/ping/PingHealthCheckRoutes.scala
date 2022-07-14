package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensus
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.healthcheck.ping._

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class PingHealthCheckRoutes[F[_]: Async: KryoSerializer](
  healthcheck: HealthCheckConsensus[
    F,
    PingHealthCheckKey,
    PingHealthCheckStatus,
    PingConsensusHealthStatus,
    PingHealthCheckConsensusDecision
  ]
) extends Http4sDsl[F] {

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req
        .as[HealthCheckRoundId]
        .flatMap(healthcheck.getOwnProposal)
        .flatMap {
          case None           => NotFound()
          case Some(proposal) => Ok(proposal)
        }

  }

  val p2pRoutes: HttpRoutes[F] = Router(
    "ping" -> p2p
  )
}
