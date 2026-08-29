package io.constellationnetwork.node.shared.http.p2p.clients

import cats.data.Kleisli
import cats.effect.{Async, Resource}
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

import _root_.io.circe.fs2._
import fs2.Stream
import fs2.io.file.{Files, Flags}
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import io.circe.{Decoder, Json}
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.`If-None-Match`
import org.typelevel.jawn.AsyncParser

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

  /** Fetch the peer's current consensus head and its exact state context.
    *
    * [[getLatest]] intentionally reads the latest durable periodic checkpoint. That is useful for ordinary historical traversal, but a
    * Currency validator joining after a long outage cannot always reconstruct every successor: deterministic Currency history may depend on
    * Global snapshots that have already left the peer's bounded rolling window. The live head already carries the state proof that commits
    * to this context, and Currency's private hand-off subsequently requires the artifact and context to match a corroborated consensus
    * outcome before installation.
    *
    * This endpoint is peer-authenticated and decoded with the same bounded-memory spool-to-disk path as the checkpoint stream.
    */
  def getLatestHead: PeerResponse[F, (Signed[S], SI)] =
    PeerResponse.stream[F, (Signed[S], SI)](uri => uri.addPath(s"$urlPrefix/latest/combined"))(client, optionalSession) { body =>
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

  /** Decode the combined-snapshot stream `[snapshotJson, stateJson]` without materializing the whole body in heap.
    *
    * Previous shape:
    *   1. `body.through(text.utf8.decode).compile.string` -- entire body becomes one Java `String` (~2x raw bytes due to UTF-16 internal
    *      representation). 2. `parser.decode[List[Json]](json)` -- Circe builds an in-memory `Json` tree (~3-4x the String size for
    *      snapshot JSON). 3. `as[Signed[S]]` and `as[SI]` -- typed decode adds another full allocation.
    *
    * Cumulative transient heap per 100 MB download: ~600-800 MB. Concurrent downloads on cluster recovery multiplied that into multi-GB
    * pressure and triggered GC pauses long enough to abandon consensus rounds.
    *
    * New shape:
    *   1. Spool the response body to a `Files[F].tempFile` Resource (chunked write, no heap accumulation, deterministic cleanup on
    *      error/cancel via the surrounding `use { ... }`). 2. Read the temp file back through
    *      `circeFs2.byteParser(AsyncParser.UnwrapArray)` -- Jawn produces one `Json` per top-level array element incrementally. 3. Decode
    *      element 1 to `Signed[S]`, then drop the source `Json` reference before pulling element 2 via `decodeFirstTwo`.
    *
    * Peak heap per concurrent download is bounded to one element's `Json` tree plus one decoded typed value, instead of the entire body
    * times three or four. The temp file is the durable backing store; the OS page cache handles re-read locality.
    *
    * The temp-file path is closed via Resource bracketing so it is freed on error, cancellation, or successful completion -- never leaked.
    */
  private def decodeCombinedBody(body: Stream[F, Byte]): F[(Signed[S], SI)] =
    SnapshotClient.decodeCombinedBodyStreaming[F, Signed[S], SI](body)

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

  /** Streaming variant of the combined-snapshot decode. Static and parameterized over the element types so unit tests can exercise the
    * spool-to-disk + incremental Jawn parse without having to construct a full `SnapshotClient` instance. The instance method
    * `decodeCombinedBody` delegates here, supplying `Signed[S]` and `SI` for `A` and `B`.
    *
    * Wire format: a single top-level JSON array of exactly two elements -- `[firstJson, secondJson]`. Empty, single-element, or 3+ element
    * bodies raise with a clear message; malformed JSON propagates from Jawn as a parse error.
    *
    * Heap discipline:
    *   1. The response body is spooled to a `Files[F].tempFile`, so the body bytes do not accumulate in heap during transfer. 2. The temp
    *      file is read back through `circeFs2.byteParser(AsyncParser.UnwrapArray)`, which emits each top-level array element as its own
    *      `Json` value -- no `List[Json]` intermediate. 3. The pull pattern decodes element 1, releases the raw `Json` reference for
    *      element 1, then pulls element 2 and decodes it. Peak resident set is one raw `Json` plus one decoded typed value, not two of
    *      each.
    *
    * The temp file is closed via Resource bracketing on every termination path (success, parse error, cancellation), so concurrent
    * downloads cannot leak temp files on disk.
    */
  def decodeCombinedBodyStreaming[F[_]: Async, A: Decoder, B: Decoder](
    body: Stream[F, Byte]
  ): F[(A, B)] =
    decodeCombinedBodyStreaming[F, A, B](body, "combined-snapshot-")

  /** Test-friendly variant of `decodeCombinedBodyStreaming` accepting a custom temp-file prefix.
    *
    * The cleanup-leak test snapshots the temp directory before/after the decode and asserts no files matching the prefix remain. Without an
    * isolating prefix the snapshot is racy with concurrent in-suite tests that all use the production `"combined-snapshot-"` prefix --
    * those other tests' in-flight temp files surface as a false-positive leak. Production calls still use the original prefix; only the
    * leak-test passes a unique value (e.g., a UUID) so its snapshot is isolated to this decode's files alone. The deletion finalizer is the
    * same `Resource.make` discipline regardless of prefix, so the prefix is a purely cosmetic partition for test determinism.
    */
  def decodeCombinedBodyStreaming[F[_]: Async, A: Decoder, B: Decoder](
    body: Stream[F, Byte],
    tempFilePrefix: String
  ): F[(A, B)] = {
    val files = Files.forAsync[F]
    // Explicit `Resource.make` over `files.tempFile` so the deletion finalizer is sequential
    // with the F[(A,B)] action: when this returns (success or error), the spool file is
    // guaranteed gone. `tempFile` in fs2 3.12 already uses Resource.make under the hood, but
    // pinning the discipline here keeps the cleanup invariant local to this helper -- the
    // cleanup-leak test pivots on a directory snapshot that compares before/after byte-identical
    // and cannot tolerate any deferred-deletion lag from upstream changes.
    Resource
      .make(files.createTempFile(None, tempFilePrefix, ".json", None))(spool => files.deleteIfExists(spool).void)
      .use { spool =>
        val parsedElements: Stream[F, Json] =
          files
            .readAll(spool, 64 * 1024, Flags.Read)
            .through(byteParser[F](AsyncParser.UnwrapArray))

        body.through(files.writeAll(spool, Flags.Write)).compile.drain >>
          decodeFirstTwo[F, A, B](parsedElements)
      }
  }

  /** Pull exactly two elements from the parsed `Json` stream, decoding each to its typed value and releasing the source `Json` before
    * pulling the next element. See [[decodeCombinedBodyStreaming]] for the heap-discipline rationale.
    */
  private def decodeFirstTwo[F[_]: Async, A: Decoder, B: Decoder](
    elements: Stream[F, Json]
  ): F[(A, B)] = {
    import fs2.Pull

    def fail(message: String): Pull[F, (A, B), Unit] =
      Pull.raiseError[F](new RuntimeException(s"Unexpected combined snapshot JSON structure: $message"))

    elements.pull.uncons1.flatMap {
      case None => fail("stream is empty")
      case Some((firstJson, tail)) =>
        Pull.eval(Async[F].fromEither(firstJson.as[A])).flatMap { first =>
          tail.pull.uncons1.flatMap {
            case None => fail("only one element, expected two")
            case Some((secondJson, rest)) =>
              Pull.eval(Async[F].fromEither(secondJson.as[B])).flatMap { second =>
                rest.pull.uncons1.flatMap {
                  case None    => Pull.output1((first, second))
                  case Some(_) => fail("more than two elements")
                }
              }
          }
        }
    }.stream.compile.lastOrError
  }
}
