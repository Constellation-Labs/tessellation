package org.tessellation.http.p2p.clients

import cats.effect.Async

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait TrustClient[F[_]] {
  // Replace with publicTrust
  def getPublicTrust: PeerResponse[F, PublicTrust]
}

object TrustClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](client: Client[F], session: Session[F]): TrustClient[F] =
    new TrustClient[F] with Http4sClientDsl[F] {

      def getPublicTrust: PeerResponse[F, PublicTrust] =
        PeerResponse[F, PublicTrust]("trust")(client, session)
    }
}
