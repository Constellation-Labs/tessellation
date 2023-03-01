package org.tessellation.sdk.http.p2p.clients

import cats.Eq
import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.domain.http.p2p.SnapshotClient
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.client.Client

trait L0GlobalSnapshotClient[
  F[_],
  T <: Transaction,
  B <: Block[T],
  S <: Snapshot[T, B],
  SI <: SnapshotInfo
] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def getLatest: PeerResponse[F, (Signed[S], SI)]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]]
}

object L0GlobalSnapshotClient {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer,
    T <: Transaction: Eq,
    B <: Block[T]: Eq: Ordering,
    S <: Snapshot[T, B]: Decoder,
    SI <: SnapshotInfo: Decoder
  ](client: Client[F]): L0GlobalSnapshotClient[F, T, B, S, SI] =
    new L0GlobalSnapshotClient[F, T, B, S, SI] {

      def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

        PeerResponse[F, SnapshotOrdinal]("global-snapshots/latest/ordinal")(client)
      }

      def getLatest: PeerResponse[F, (Signed[S], SI)] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, (Signed[S], SI)]("global-snapshots/latest/combined")(client)
      }

      def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[S]](s"global-snapshots/${ordinal.value.value}")(client)
      }
    }
}
