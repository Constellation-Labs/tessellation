package org.tesselation.http.p2p.clients

import cats.effect.Concurrent
import cats.syntax.functor._

import org.tesselation.http.p2p.PeerResponse
import org.tesselation.http.p2p.PeerResponse.PeerResponse
import org.tesselation.schema.peer.{JoinRequest, RegistrationRequest, SignRequest}
import org.tesselation.security.signature.Signed

import org.http4s.Method._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl

trait SignClient[F[_]] {
  def sign(signRequest: SignRequest): PeerResponse[F, Signed[SignRequest]]
  def joinRequest(jr: JoinRequest): PeerResponse[F, Unit]
  def getRegistrationRequest: PeerResponse[F, RegistrationRequest]
}

object SignClient {

  def make[F[_]: Concurrent](client: Client[F]): SignClient[F] =
    new SignClient[F] with Http4sClientDsl[F] {

      def getRegistrationRequest: PeerResponse[F, RegistrationRequest] =
        PeerResponse[F, RegistrationRequest]("registration/request")(client)

      def joinRequest(jr: JoinRequest): PeerResponse[F, Unit] =
        PeerResponse[F, Unit]("cluster/join", POST)(client) { (req, c) =>
          c.successful(req.withEntity(jr)).void
        }

      def sign(signRequest: SignRequest): PeerResponse[F, Signed[SignRequest]] =
        PeerResponse[F, Signed[SignRequest]]("registration/sign", POST)(client) { (req, c) =>
          c.expect[Signed[SignRequest]](req.withEntity(signRequest))
        }
    }
}
