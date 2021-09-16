package org.tesselation.http.p2p

import cats.effect.Concurrent

import org.tesselation.http.p2p.clients.SignClient

import org.http4s.client._

trait P2PClient[F[_]] {
  val sign: SignClient[F]
}

object P2PClient {

  def make[F[_]: Concurrent](
    client: Client[F]
  ): P2PClient[F] =
    new P2PClient[F] {
      val sign: SignClient[F] = SignClient.make[F](client)
    }
}
