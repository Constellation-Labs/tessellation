package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.sdk.infrastructure.consensus.registration.RegistrationResponse

import io.circe.{Decoder, Encoder}
import org.http4s.Method.GET
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client

trait ConsensusClient[F[_], Key] {

  def getRegistration: PeerResponse[F, RegistrationResponse[Key]]

}

object ConsensusClient {
  def make[F[_]: Async: KryoSerializer, Key: Encoder: Decoder](client: Client[F], session: Session[F]): ConsensusClient[F, Key] =
    new ConsensusClient[F, Key] {
      def getRegistration: PeerResponse[F, RegistrationResponse[Key]] = PeerResponse("consensus/registration", GET)(client, session) {
        (req, c) =>
          c.expect[RegistrationResponse[Key]](req)
      }
    }
}
