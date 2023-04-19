package org.tessellation.currency.l0.snapshot

import cats.effect.Async

import org.tessellation.currency.schema.currency._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.snapshot.SnapshotMetadata
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.client.Client

trait CurrencySnapshotClient[F[_]] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def getLatestMetadata: PeerResponse[F, SnapshotMetadata]
  def getLatest: PeerResponse[F, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[CurrencyIncrementalSnapshot]]
  def get(hash: Hash): PeerResponse[F, Signed[CurrencyIncrementalSnapshot]]
}

object CurrencySnapshotClient {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer
  ](client: Client[F]): CurrencySnapshotClient[F] =
    new CurrencySnapshotClient[F] {

      def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

        PeerResponse[F, SnapshotOrdinal]("snapshots/latest/ordinal")(client)
      }

      def getLatestMetadata: PeerResponse[F, SnapshotMetadata] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, SnapshotMetadata]("snapshots/latest/metadata")(client)
      }

      def getLatest: PeerResponse[F, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]("snapshots/latest/combined")(client)
      }

      def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[CurrencyIncrementalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[CurrencyIncrementalSnapshot]](s"snapshots/${ordinal.value.value}")(client)
      }

      def get(hash: Hash): PeerResponse[F, Signed[CurrencyIncrementalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[CurrencyIncrementalSnapshot]](s"snapshots/$hash")(client)
      }

    }
}
