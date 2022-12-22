package org.tessellation.sdk.http.p2p.clients

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.schema.peer.{JoinRequest, RegistrationRequest, SignRequest}
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.Method._
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait SignClient[F[_]] {
  def sign(signRequest: SignRequest): PeerResponse[F, Signed[SignRequest]]
  def joinRequest(jr: JoinRequest): PeerResponse[F, Boolean]
  def getRegistrationRequest: PeerResponse[F, RegistrationRequest]
}

object SignClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F]): SignClient[F] =
    new SignClient[F] with Http4sClientDsl[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def getRegistrationRequest: PeerResponse[F, RegistrationRequest] =
        PeerResponse("registration/request")(client)

      def joinRequest(jr: JoinRequest): PeerResponse[F, Boolean] =
        PeerResponse("cluster/join", POST)(client) { (req, c) =>
          c.run(req.withEntity(jr)).use {
            case Status.Successful(_) => Applicative[F].pure(true)
            case res                  => res.as[String].flatTap(msg => logger.warn(s"Join request rejected due to: $msg")).as(false)
          }
        }

      def sign(signRequest: SignRequest): PeerResponse[F, Signed[SignRequest]] =
        PeerResponse("registration/sign", POST)(client) { (req, c) =>
          c.expect[Signed[SignRequest]](req.withEntity(signRequest))
        }
    }
}
