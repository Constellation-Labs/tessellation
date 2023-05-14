package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.snapshot.SnapshotMetadata
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.Method.POST
import org.http4s.Uri
import org.http4s.client.Client

trait L0GlobalSnapshotClient[F[_]] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def getLatestMetadata: PeerResponse[F, SnapshotMetadata]
  def getLatest: PeerResponse[F, (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalIncrementalSnapshot]]
  def get(hash: Hash): PeerResponse[F, Signed[GlobalIncrementalSnapshot]]
  def getHash(ordinal: SnapshotOrdinal): PeerResponse[F, Option[Hash]]
  def getFull(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalSnapshot]]
}

object L0GlobalSnapshotClient {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer
  ](client: Client[F], session: Session[F]): L0GlobalSnapshotClient[F] =
    new L0GlobalSnapshotClient[F] {

      def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

        PeerResponse[F, SnapshotOrdinal]("global-snapshots/latest/ordinal")(client)
      }

      def getLatestMetadata: PeerResponse[F, SnapshotMetadata] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, SnapshotMetadata]("global-snapshots/latest/metadata")(client)
      }

      def getLatest: PeerResponse[F, (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]("global-snapshots/latest/combined")(client)
      }

      def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalIncrementalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[GlobalIncrementalSnapshot]](s"global-snapshots/${ordinal.value.value}")(client)
      }

      def getHash(ordinal: SnapshotOrdinal): PeerResponse[F, Option[Hash]] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse(s"global-snapshots/${ordinal.value.value}/hash", POST)(client, session) { (req, client) =>
          client.expectOption[Hash](req)
        }
      }

      def get(hash: Hash): PeerResponse[F, Signed[GlobalIncrementalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[GlobalIncrementalSnapshot]](s"global-snapshots/$hash")(client)
      }

      def getFull(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[GlobalSnapshot]]((uri: Uri) =>
          uri.addPath(s"global-snapshots/${ordinal.value.value}").withQueryParam("full")
        )(client)
      }
    }
}
