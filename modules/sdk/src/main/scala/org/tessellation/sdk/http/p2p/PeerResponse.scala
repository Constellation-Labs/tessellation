package org.tessellation.sdk.http.p2p

import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.option._

import org.tessellation.schema.peer.P2PContext
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.middleware.PeerAuthMiddleware
import org.tessellation.security.SecurityProvider

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

    def apply(client: Client[F], session: Session[F])(
      f: (Request[F], Client[F]) => F[A]
    )(implicit F: Async[F]): PeerResponse[F, A] =
      apply(PeerAuthMiddleware.responseTokenVerifierMiddleware[F](client, session))(f)
  }

  def apply[F[_], A](path: String) = new {

    def apply(
      client: Client[F]
    )(implicit decoder: EntityDecoder[F, A], F: Async[F], S: SecurityProvider[F]): PeerResponse[F, A] =
      Kleisli.apply { peer =>
        val verified = PeerAuthMiddleware.responseVerifierMiddleware[F](peer.id)(client)
        verified.expect[A](getUri(peer, path))
      }

    def apply(
      client: Client[F],
      session: Session[F]
    )(implicit decoder: EntityDecoder[F, A], F: Async[F], S: SecurityProvider[F]): PeerResponse[F, A] =
      apply(PeerAuthMiddleware.responseTokenVerifierMiddleware[F](client, session))
  }
}
