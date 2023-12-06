package org.tessellation.node.shared.http.p2p.clients

import cats.effect.Async

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.http.p2p.PeerResponse
import org.tessellation.node.shared.http.p2p.PeerResponse.PeerResponse
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait TrustClient[F[_]] {
  def getPublicTrust: PeerResponse[F, PublicTrust]
}

object TrustClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](client: Client[F], session: Session[F]): TrustClient[F] =
    new TrustClient[F] with Http4sClientDsl[F] {

      def getPublicTrust: PeerResponse[F, PublicTrust] =
        PeerResponse[F, PublicTrust]("trust")(client, session)
    }
}
