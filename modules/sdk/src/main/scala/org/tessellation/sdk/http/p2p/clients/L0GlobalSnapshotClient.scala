package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalSnapshotInfo, IncrementalGlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.client.Client

trait L0GlobalSnapshotClient[
  F[_]
] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def getLatest: PeerResponse[F, (Signed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[IncrementalGlobalSnapshot]]
  def get(hash: Hash): PeerResponse[F, Signed[IncrementalGlobalSnapshot]]
}

object L0GlobalSnapshotClient {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer
  ](client: Client[F]): L0GlobalSnapshotClient[F] =
    new L0GlobalSnapshotClient[F] {

      def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

        PeerResponse[F, SnapshotOrdinal]("global-snapshots/latest/ordinal")(client)
      }

      def getLatest: PeerResponse[F, (Signed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, (Signed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)]("global-snapshots/latest/combined")(client)
      }

      def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[IncrementalGlobalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[IncrementalGlobalSnapshot]](s"global-snapshots/${ordinal.value.value}")(client)
      }

      def get(hash: Hash): PeerResponse[F, Signed[IncrementalGlobalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[IncrementalGlobalSnapshot]](s"global-snapshots/$hash")(client)
      }
    }
}
