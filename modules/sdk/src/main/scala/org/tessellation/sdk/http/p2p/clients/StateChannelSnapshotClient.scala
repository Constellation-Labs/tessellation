package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait StateChannelSnapshotClient[F[_]] {
  def send(identifier: Address, data: Signed[StateChannelSnapshotBinary]): PeerResponse[F, Boolean]
}

object StateChannelSnapshotClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    client: Client[F]
  ): StateChannelSnapshotClient[F] =
    new StateChannelSnapshotClient[F] {

      def send(identifier: Address, data: Signed[StateChannelSnapshotBinary]): PeerResponse[F, Boolean] =
        PeerResponse(s"state-channels/${identifier.value.value}/snapshot", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data))
        }
    }
}
