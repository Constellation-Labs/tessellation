package org.tessellation.sdk.http.p2p

import cats.data.Kleisli
import cats.syntax.option._

import org.tessellation.schema.peer.P2PContext

import org.http4s.Uri.{Authority, RegName, Scheme}
import org.http4s._
import org.http4s.client.Client

object PeerResponse {
  type PeerResponse[F[_], A] = Kleisli[F, P2PContext, A]

  def getUri(peer: P2PContext, path: String): Uri =
    Uri(
      scheme = Scheme.http.some,
      authority = Authority(
        host = RegName(peer.ip.toString),
        port = peer.port.value.some
      ).some
    ).addPath(path)

  def apply[F[_], A](path: String, method: Method) = new {

    def apply(client: Client[F])(f: (Request[F], Client[F]) => F[A]): PeerResponse[F, A] =
      Kleisli.apply { peer =>
        val req = Request[F](method = method, uri = getUri(peer, path))
        f(req, client)
      }
  }

  def apply[F[_], A](path: String) = new {

    def apply(client: Client[F])(implicit decoder: EntityDecoder[F, A]): PeerResponse[F, A] =
      Kleisli.apply { peer =>
        client.expect[A](getUri(peer, path))
      }
  }
}
