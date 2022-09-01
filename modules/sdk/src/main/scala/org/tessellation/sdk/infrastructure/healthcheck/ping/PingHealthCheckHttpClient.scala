package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.option._

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.Method.POST
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait PingHealthCheckHttpClient[F[_]] {
  def requestProposal(
    roundIds: Set[HealthCheckRoundId],
    ownProposal: PingConsensusHealthStatus
  ): PeerResponse[F, Option[PingConsensusHealthStatus]]
}

object PingHealthCheckHttpClient {

  def make[F[_]: Async: KryoSerializer](client: Client[F], session: Session[F]): PingHealthCheckHttpClient[F] =
    new PingHealthCheckHttpClient[F] with Http4sClientDsl[F] {

      def requestProposal(
        roundIds: Set[HealthCheckRoundId],
        ownProposal: PingConsensusHealthStatus
      ): PeerResponse[F, Option[PingConsensusHealthStatus]] =
        PeerResponse[F, PingConsensusHealthStatus](s"healthcheck/ping", POST)(client, session) { (req, c) =>
          c.expect[PingConsensusHealthStatus](req.withEntity((roundIds, ownProposal)))
        }.map(_.some).handleError(_ => none)
    }
}
