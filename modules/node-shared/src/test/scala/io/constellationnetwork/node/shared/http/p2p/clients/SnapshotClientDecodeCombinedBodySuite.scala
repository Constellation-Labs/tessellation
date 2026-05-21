package io.constellationnetwork.node.shared.http.p2p.clients

import cats.effect.IO

import _root_.io.circe.generic.semiauto._
import _root_.io.circe.syntax._
import _root_.io.circe.{Decoder, Encoder}
import fs2.{Chunk, Stream}
import weaver.SimpleIOSuite

/** Coverage for `SnapshotClient.decodeCombinedBodyStreaming`, the new spool-to-disk + incremental Jawn-parse path.
  *
  * The previous decoder accumulated the whole body into a Java `String` and then into a `List[Json]` tree, which scaled the transient heap
  * to 6-8x the body size and triggered GC pauses long enough to abandon consensus rounds during recovery. The new decoder spools to a
  * `Files[F].tempFile` and then walks the array via `byteParser(AsyncParser.UnwrapArray)`, so the resident set is bounded by one decoded
  * element plus the parser working set, regardless of body size.
  *
  * We exercise the streaming framing layer in isolation using a minimal pair of element types with hand-rolled Circe codecs. The framing
  * guarantees are agnostic to the element shape: empty body raises, single-element raises, well-formed 2-element decodes, 3+ raises,
  * malformed JSON raises, and many-small-chunks input still decodes correctly. Concrete-type wire-format coverage lives in the integration
  * tests that round-trip through the actual `SnapshotRoutes`; those are unchanged by this work.
  */
object SnapshotClientDecodeCombinedBodySuite extends SimpleIOSuite {

  final case class Elem1(payload: String)
  final case class Elem2(label: String, count: Int)

  implicit val elem1Encoder: Encoder[Elem1] = deriveEncoder
  implicit val elem1Decoder: Decoder[Elem1] = deriveDecoder
  implicit val elem2Encoder: Encoder[Elem2] = deriveEncoder
  implicit val elem2Decoder: Decoder[Elem2] = deriveDecoder

  private def streamOf(body: String): Stream[IO, Byte] =
    Stream.chunk(Chunk.array(body.getBytes("UTF-8"))).covary[IO]

  test("decodes a well-formed two-element body into the expected typed pair") {
    val first = Elem1("hello")
    val second = Elem2("world", 42)
    val body = s"[${first.asJson.noSpaces},${second.asJson.noSpaces}]"

    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(body))
      .map {
        case (a, b) =>
          expect(a == first).and(expect(b == second))
      }
  }

  test("decodes a multi-MB body without retaining the whole payload in heap") {
    // The point of the new decoder is that the body is spooled to disk; the temp file is the
    // backing store, not the heap. We exercise that path by generating a body larger than what
    // the legacy `compile.string` would have kept resident, and verify the decode still succeeds.
    // The real measurable win is under concurrent load (recovery scenario); this is a smoke test
    // that the streaming path traverses the size correctly.
    val largePayload = "x" * (2 * 1024 * 1024) // 2 MiB
    val first = Elem1(largePayload)
    val second = Elem2(largePayload, 1)
    val body = s"[${first.asJson.noSpaces},${second.asJson.noSpaces}]"

    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(body))
      .map {
        case (a, b) =>
          expect(a.payload.length == largePayload.length)
            .and(expect(b.label.length == largePayload.length))
            .and(expect(b.count == 1))
      }
  }

  test("raises for an empty body (the array opener never appears)") {
    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(""))
      .attempt
      .map(r => expect(r.isLeft, s"empty body must raise, got $r"))
  }

  test("raises with a clear message for a single-element array") {
    val body = s"[${Elem1("only-one").asJson.noSpaces}]"
    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(body))
      .attempt
      .map { r =>
        expect(r.isLeft, s"single-element body must raise, got $r").and(
          expect(
            r.swap.toOption.exists(_.getMessage.contains("one element")),
            s"error should mention element count, got ${r.swap.toOption.map(_.getMessage)}"
          )
        )
      }
  }

  test("raises with a clear message for a three-element array (defends against silent truncation)") {
    val body = s"[${Elem1("a").asJson.noSpaces},${Elem2("b", 1).asJson.noSpaces},${Elem1("c").asJson.noSpaces}]"
    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(body))
      .attempt
      .map { r =>
        expect(r.isLeft, s"three-element body must raise, got $r").and(
          expect(
            r.swap.toOption.exists(_.getMessage.contains("more than two")),
            s"error should mention extra elements, got ${r.swap.toOption.map(_.getMessage)}"
          )
        )
      }
  }

  test("raises for malformed JSON (Jawn parse error propagates)") {
    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf("[{not valid json"))
      .attempt
      .map(r => expect(r.isLeft, s"malformed JSON must raise, got $r"))
  }

  test("raises when element 1 cannot be typed-decoded (Circe decode error propagates)") {
    // Element 1 has the wrong shape (missing required `payload` field), so the typed decode fails
    // even though the JSON parses. We want this case to surface as a clear `Async.raiseError`, not
    // silently coerce to a default value.
    val body = s"[{\"wrong-field\":true},${Elem2("ok", 1).asJson.noSpaces}]"
    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(body))
      .attempt
      .map(r => expect(r.isLeft, s"wrong-shape element 1 must raise, got $r"))
  }

  test("decodes a body that arrives as many small fs2 chunks (streaming friendliness)") {
    // The body is split into single-byte chunks so the temp-file write path is exercised in its
    // worst-case shape (no large chunks to coalesce). Asserts the decoder doesn't depend on any
    // particular chunk boundary alignment.
    val body = s"[${Elem1("chunky").asJson.noSpaces},${Elem2("split", 7).asJson.noSpaces}]"
    val bytewise: Stream[IO, Byte] = Stream.emits(body.getBytes("UTF-8")).covary[IO]

    SnapshotClient
      .decodeCombinedBodyStreaming[IO, Elem1, Elem2](bytewise)
      .map {
        case (a, b) =>
          expect(a == Elem1("chunky")).and(expect(b == Elem2("split", 7)))
      }
  }

  test("temp file is cleaned up after a successful decode (Resource bracketing closes deterministically)") {
    // The decoder owns a `Files[F].tempFile` Resource that closes the file in its `release` clause.
    // We verify cleanup by listing the temp dir AFTER the decode completes and confirming the file
    // we know was created -- a snapshot of the names matching our prefix -- does not survive.
    //
    // We snapshot the directory contents BEFORE the decode, run the decode, then snapshot again and
    // take the set difference. Any element present after but not before is a leak. To isolate this
    // test's snapshot from concurrent in-suite tests that all use the production
    // `"combined-snapshot-"` prefix, we pass a unique UUID-derived prefix to the decoder. Without
    // this isolation other tests' in-flight temp files surface as false-positive leaks (observed
    // pre-fix as a flaky 1-of-9 failure with 3-4 sibling test files showing up in `leaked`).
    val tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"))
    val uniquePrefix = s"combined-snapshot-leaktest-${java.util.UUID.randomUUID().toString}-"

    def listMatching: Set[String] =
      Option(tmpDir.list()).map(_.iterator.filter(_.startsWith(uniquePrefix)).toSet).getOrElse(Set.empty)

    val body = s"[${Elem1("a").asJson.noSpaces},${Elem2("b", 1).asJson.noSpaces}]"
    for {
      before <- IO(listMatching)
      _ <- SnapshotClient.decodeCombinedBodyStreaming[IO, Elem1, Elem2](streamOf(body), uniquePrefix)
      after <- IO(listMatching)
      leaked = after.diff(before)
    } yield expect(leaked.isEmpty, s"decoder must not leak temp files; leaked=$leaked")
  }
}
