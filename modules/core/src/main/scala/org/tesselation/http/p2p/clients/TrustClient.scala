package org.tesselation.http.p2p.clients

import cats.effect.Concurrent

import org.tesselation.ext.codecs.BinaryCodec._
import org.tesselation.http.p2p.PeerResponse
import org.tesselation.http.p2p.PeerResponse.PeerResponse
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.trust.PublicTrust

import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait TrustClient[F[_]] {
  // Replace with publicTrust
  def getPublicTrust: PeerResponse[F, PublicTrust]
}

object TrustClient {

  def make[F[_]: Concurrent: KryoSerializer](client: Client[F]): TrustClient[F] =
    new TrustClient[F] with Http4sClientDsl[F] {

      def getPublicTrust: PeerResponse[F, PublicTrust] =
        PeerResponse[F, PublicTrust]("trust")(client)
    }
}
