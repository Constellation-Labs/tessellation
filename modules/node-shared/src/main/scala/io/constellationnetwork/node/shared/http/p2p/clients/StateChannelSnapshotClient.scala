package io.constellationnetwork.node.shared.http.p2p.clients

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.{EntityDecoder, Status}

trait StateChannelSnapshotClient[F[_]] {
  def send(
    identifier: Address,
    data: Signed[StateChannelSnapshotBinary]
  ): PeerResponse[F, Either[NonEmptyList[StateChannelValidationError], Unit]]
}

object StateChannelSnapshotClient {

  def make[F[_]: Async](
    client: Client[F]
  ): StateChannelSnapshotClient[F] =
    new StateChannelSnapshotClient[F] {

      def send(
        identifier: Address,
        data: Signed[StateChannelSnapshotBinary]
      ): PeerResponse[F, Either[NonEmptyList[StateChannelValidationError], Unit]] =
        PeerResponse(s"state-channels/${identifier.value.value}/snapshot", POST)(client) { (req, c) =>
          c.run(req.withEntity(data)).use { resp =>
            resp.status match {
              case Status.Ok => ().asRight[NonEmptyList[StateChannelValidationError]].pure[F]
              case Status.BadRequest =>
                EntityDecoder[F, NonEmptyList[StateChannelValidationError]]
                  .decode(resp, strict = false)
                  .leftWiden[Throwable]
                  .rethrowT
                  .map(_.asLeft[Unit])
              case _ => Async[F].raiseError(UnexpectedStatus(resp.status, req.method, req.uri))
            }
          }
        }
    }
}
