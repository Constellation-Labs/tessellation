package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import io.circe.{Decoder, Encoder}
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client

trait ConsensusClient[F[_], Key] {

  def exchangeRegistration(request: RegistrationExchangeRequest[Key]): PeerResponse[F, RegistrationExchangeResponse[Key]]

}

object ConsensusClient {
  def make[F[_]: Async: KryoSerializer, Key: Encoder: Decoder](client: Client[F], session: Session[F]): ConsensusClient[F, Key] =
    (request: RegistrationExchangeRequest[Key]) =>
      PeerResponse("consensus/registration", POST)(client, session) { (req, c) =>
        c.expect[RegistrationExchangeResponse[Key]](req.withEntity(request))
      }
}
