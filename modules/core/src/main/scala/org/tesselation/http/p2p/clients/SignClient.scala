package org.tesselation.http.p2p.clients

import cats.effect.Concurrent

import org.tesselation.http.p2p.PeerResponse
import org.tesselation.http.p2p.PeerResponse.PeerResponse
import org.tesselation.schema.peer.{JoinRequest, RegistrationRequest}

import org.http4s.Method._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl

trait SignClient[F[_]] {
  def sign: PeerResponse[F, Unit]
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
          c.expect[Unit](req.withEntity(jr))
        }

      def sign: PeerResponse[F, Unit] =
        PeerResponse[F, Unit]("sign", POST)(client) { (req, c) =>
          c.expect[Unit](req.withEntity(""))
        }
    }
}
