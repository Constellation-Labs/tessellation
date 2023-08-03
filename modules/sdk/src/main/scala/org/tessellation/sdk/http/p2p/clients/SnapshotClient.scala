package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, SnapshotMetadata}
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.Method.GET
import org.http4s.client.Client

abstract class SnapshotClient[
  F[_]: Async: SecurityProvider: KryoSerializer,
  S <: Snapshot[_]: Decoder,
  SI <: SnapshotInfo[_]: Decoder
] {
  def client: Client[F]
  def optionalSession: Option[Session[F]]
  def urlPrefix: String

  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

    PeerResponse[F, SnapshotOrdinal](s"$urlPrefix/latest/ordinal")(client, optionalSession)
  }

  def getLatestMetadata: PeerResponse[F, SnapshotMetadata] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    PeerResponse[F, SnapshotMetadata](s"$urlPrefix/latest/metadata")(client, optionalSession)
  }

  def getLatest: PeerResponse[F, (Signed[S], SI)] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    PeerResponse[F, (Signed[S], SI)](s"$urlPrefix/latest/combined")(client, optionalSession)
  }

  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]] = {
    import org.tessellation.ext.codecs.BinaryCodec.decoder

    PeerResponse[F, Signed[S]](s"$urlPrefix/${ordinal.value.value}")(client, optionalSession)
  }

  def get(hash: Hash): PeerResponse[F, Signed[S]] = {
    import org.tessellation.ext.codecs.BinaryCodec.decoder

    PeerResponse[F, Signed[S]](s"$urlPrefix/$hash")(client, optionalSession)
  }

  def getHash(ordinal: SnapshotOrdinal): PeerResponse[F, Option[Hash]] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    PeerResponse(s"$urlPrefix/${ordinal.value.value}/hash", GET)(client, optionalSession) { (req, client) =>
      client.expectOption[Hash](req)
    }
  }
}
