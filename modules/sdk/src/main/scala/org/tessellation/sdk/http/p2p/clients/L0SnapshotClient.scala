package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.sdk.domain.http.p2p.SnapshotClient
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.client.Client

object L0SnapshotClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer, S <: Snapshot[_, _]](client: Client[F]): SnapshotClient[F, S] =
    new SnapshotClient[F, S] {

      def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

        PeerResponse[F, SnapshotOrdinal]("snapshots/latest/ordinal")(client)
      }

      def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[S]](s"snapshots/${ordinal.value.value}")(client)
      }
    }
}
