package io.constellationnetwork.node.shared.http.p2p.clients

import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.middlewares.PeerAuthMiddleware
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, SnapshotMetadata}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import fs2.text
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import io.circe.{Decoder, Json, parser}
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.`If-None-Match`

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
    PeerResponse.stream[F, (Signed[S], SI)](uri => uri.addPath(s"$urlPrefix/latest/combined/stream"))(client, optionalSession) { body =>
      decodeCombinedBody(body)
    }

  /** Conditional variant of [[getLatest]]. The client sends `If-None-Match: "<localOrdinal>-<localHash>"`; the server returns 304
    * NotModified (no body) when its tip matches the immutable identity `(ordinal, snapshotHash)` and 200 with a fresh combined-snapshot
    * stream otherwise.
    *
    * The ETag value encodes the FULL immutable identity, not just the ordinal — different forks can produce different bytes at the same
    * ordinal, so an ordinal-only validator could falsely 304 against canonical (N, H₂) when the client cached stale (N, H₁). Bundling the
    * hash makes the conditional path correct under fork-recovery.
    *
    * Saves the ~60 MB body when a metagraph is already aligned with the L0 cluster's tip, which is the common case during steady-state. The
    * server-side ETag/304 path lives at `SnapshotRoutes.scala:137`. The 304-vs-200 distinction is encoded in the result type so the caller
    * can short-circuit its apply pipeline without needing to compare ordinals after-the-fact.
    *
    * Wire-format note: `If-None-Match` is purely additive — old servers ignore the header and always return 200, so this is
    * forward+backward compatible across mixed-version clusters. v10.x ETag value format `"<ord>-<hash>"` is also strictly compatible: an
    * old client sending `"<ord>"` to a new server gets a hash mismatch and returns 200 (correct fallback); a new client sending the full
    * format to an old server also gets a mismatch and returns 200.
    */
  def getLatestConditional(
    localOrdinal: SnapshotOrdinal,
    localHash: Hash
  ): PeerResponse[F, Either[SnapshotClient.NotModified.type, (Signed[S], SI)]] = {
    val tag = EntityTag(s"${localOrdinal.value.value}-${localHash.value}", EntityTag.Strong)
    val ifNoneMatch = `If-None-Match`(Some(cats.data.NonEmptyList.one(tag)))

    Kleisli { peer =>
      val req = Request[F](method = GET, uri = PeerResponse.getUri(peer, s"$urlPrefix/latest/combined/stream"))
        .putHeaders(ifNoneMatch)
      val verified = optionalSession match {
        case None          => PeerAuthMiddleware.responseVerifierMiddleware[F](peer.id)(client)
        case Some(session) => PeerAuthMiddleware.responseTokenVerifierMiddleware[F](client, session)
      }
      verified.run(req).use(resp => SnapshotClient.handleConditionalResponse[F, S, SI](resp, decodeCombinedBody))
    }
  }

  private def decodeCombinedBody(body: fs2.Stream[F, Byte]): F[(Signed[S], SI)] =
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

object SnapshotClient {

  /** Sentinel returned by [[SnapshotClient.getLatestConditional]] when the server's tip equals the client's `If-None-Match` ordinal — i.e.,
    * the caller is already current and there is no snapshot body to apply.
    */
  case object NotModified

  /** Pure response-handler for the conditional combined-stream fetch. Extracted from the SnapshotClient instance method so unit tests can
    * exercise the 304-vs-200 branch logic without standing up the full PeerAuth + http4s client stack. The `decodeBody` parameter is the
    * caller's body decoder — passed in as a function so this helper stays free of `S`/`SI` instance dependencies.
    */
  def handleConditionalResponse[F[_]: Async, S <: Snapshot, SI <: SnapshotInfo[_]](
    resp: Response[F],
    decodeBody: fs2.Stream[F, Byte] => F[(Signed[S], SI)]
  ): F[Either[NotModified.type, (Signed[S], SI)]] =
    resp.status match {
      case Status.NotModified => (Left(NotModified): Either[NotModified.type, (Signed[S], SI)]).pure[F]
      case Status.Ok          => decodeBody(resp.body).map(Right(_))
      case other =>
        Async[F].raiseError[Either[NotModified.type, (Signed[S], SI)]](
          new RuntimeException(s"Unexpected response status from latest/combined/stream: $other")
        )
    }
}
