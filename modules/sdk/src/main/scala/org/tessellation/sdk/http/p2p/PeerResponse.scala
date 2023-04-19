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

  def getBaseUri(peer: P2PContext): Uri =
    Uri(
      scheme = Scheme.http.some,
      authority = Authority(
        host = RegName(peer.ip.toString),
        port = peer.port.value.some
      ).some
    )

  def getUri(peer: P2PContext, path: String): Uri =
    getBaseUri(peer).addPath(path)

  private[p2p] final class PeerResponsePartaillyApplied[F[_], G[_], A](val uri: Uri => Uri, method: Method) {

    def apply(client: Client[F])(f: (Request[F], Client[F]) => G[A]): PeerResponse[G, A] =
      Kleisli.apply { peer =>
        val req = Request[F](method = method, uri = uri(getBaseUri(peer)))
        f(req, client)
      }

    def apply(client: Client[F], session: Session[F])(
      f: (Request[F], Client[F]) => G[A]
    )(implicit F: Async[F]): PeerResponse[G, A] =
      apply(PeerAuthMiddleware.responseTokenVerifierMiddleware[F](client, session))(f)

  }

  def apply[F[_], G[_], A](uri: Uri => Uri, method: Method) =
    new PeerResponsePartaillyApplied[F, G, A](uri, method)
  def apply[F[_], G[_], A](path: String, method: Method) =
    new PeerResponsePartaillyApplied[F, G, A]((uri: Uri) => uri.addPath(path), method)

  private[p2p] final class GetPeerResponsePartaillyApplied[F[_], A](val uri: Uri => Uri) {
    def apply(
      client: Client[F]
    )(implicit decoder: EntityDecoder[F, A], F: Async[F], S: SecurityProvider[F]): PeerResponse[F, A] =
      Kleisli.apply { peer =>
        val verified = PeerAuthMiddleware.responseVerifierMiddleware[F](peer.id)(client)
        verified.expect[A](getBaseUri(peer))
      }

    def apply(
      client: Client[F],
      session: Session[F]
    )(implicit decoder: EntityDecoder[F, A], F: Async[F], S: SecurityProvider[F]): PeerResponse[F, A] =
      apply(PeerAuthMiddleware.responseTokenVerifierMiddleware[F](client, session))
  }

  def apply[F[_], A](uri: Uri => Uri) = new GetPeerResponsePartaillyApplied[F, A](uri)
  def apply[F[_], A](path: String) = new GetPeerResponsePartaillyApplied[F, A]((uri: Uri) => uri.addPath(path))

  private[p2p] final class SuccessfulPeerResponsePartaillyApplied[F[_]](val uri: Uri => Uri, method: Method) {

    def apply(
      client: Client[F]
    )(implicit F: Async[F], S: SecurityProvider[F]): PeerResponse[F, Boolean] =
      Kleisli.apply[F, P2PContext, Boolean] { peer =>
        val req = Request[F](method = method, uri = getBaseUri(peer))
        val verified = PeerAuthMiddleware.responseVerifierMiddleware[F](peer.id)(client)

        verified.successful(req)
      }

    def apply(
      client: Client[F],
      session: Session[F]
    )(implicit F: Async[F], S: SecurityProvider[F]): PeerResponse[F, Boolean] =
      apply(PeerAuthMiddleware.responseTokenVerifierMiddleware[F](client, session))
  }

  def successful[F[_]](path: String): SuccessfulPeerResponsePartaillyApplied[F] =
    new SuccessfulPeerResponsePartaillyApplied[F]((uri: Uri) => uri.addPath(path), Method.GET)
  def successful[F[_]](uri: Uri => Uri): SuccessfulPeerResponsePartaillyApplied[F] =
    new SuccessfulPeerResponsePartaillyApplied[F](uri, Method.GET)

  def successful[F[_]](path: String, method: Method): SuccessfulPeerResponsePartaillyApplied[F] =
    new SuccessfulPeerResponsePartaillyApplied[F]((uri: Uri) => uri.addPath(path), method)
  def successful[F[_]](uri: Uri => Uri, method: Method): SuccessfulPeerResponsePartaillyApplied[F] =
    new SuccessfulPeerResponsePartaillyApplied[F](uri, method)
}
