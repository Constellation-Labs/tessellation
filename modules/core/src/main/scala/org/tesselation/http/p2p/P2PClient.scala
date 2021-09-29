package org.tesselation.http.p2p

import cats.effect.Concurrent

import org.tesselation.http.p2p.clients.{ClusterClient, SignClient}

import org.http4s.client._

trait P2PClient[F[_]] {
  val sign: SignClient[F]
  val cluster: ClusterClient[F]
}

object P2PClient {

  def make[F[_]: Concurrent](
    client: Client[F]
  ): P2PClient[F] =
    new P2PClient[F] {
      val sign: SignClient[F] = SignClient.make[F](client)
      val cluster: ClusterClient[F] = ClusterClient.make[F](client)
    }
}
