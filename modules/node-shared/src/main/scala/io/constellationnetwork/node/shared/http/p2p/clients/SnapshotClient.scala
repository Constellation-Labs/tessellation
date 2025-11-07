package io.constellationnetwork.node.shared.http.p2p.clients

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, SnapshotMetadata}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import fs2.text
import io.circe.fs2._
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import io.circe.{Decoder, Json, parser}
import org.http4s.Method.GET
import org.http4s.client.Client

abstract class SnapshotClient[
  F[_]: Async: SecurityProvider,
  S <: Snapshot: Decoder,
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

  def getLatest: PeerResponse[F, (Signed[S], SI)] =
    PeerResponse.stream[F, (Signed[S], SI)](uri => uri.addPath(s"$urlPrefix/latest/combined"))(client, optionalSession) { body =>
      body
        .through(text.utf8.decode)
        .compile
        .string
        .flatMap { json =>
          for {
            arr <- Async[F].fromEither(parser.decode[List[Json]](json))
            tuple <- arr match {
              case List(snapshotJson, stateJson) =>
                for {
                  snapshot <- Async[F].fromEither(snapshotJson.as[Signed[S]])
                  state <- Async[F].fromEither(stateJson.as[SI])
                } yield (snapshot, state)
              case other =>
                Async[F].raiseError[(Signed[S], SI)](
                  new RuntimeException(s"Unexpected combined snapshot JSON structure: $other")
                )
            }
          } yield tuple
        }
    }

  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    PeerResponse[F, Signed[S]](s"$urlPrefix/${ordinal.value.value}")(client, optionalSession)
  }

  def get(hash: Hash): PeerResponse[F, Signed[S]] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    PeerResponse[F, Signed[S]](s"$urlPrefix/$hash")(client, optionalSession)
  }

  def getHash(ordinal: SnapshotOrdinal): PeerResponse[F, Option[Hash]] = {
    import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

    PeerResponse(s"$urlPrefix/${ordinal.value.value}/hash", GET)(client, optionalSession) { (req, client) =>
      client.expectOption[Hash](req)
    }
  }
}
