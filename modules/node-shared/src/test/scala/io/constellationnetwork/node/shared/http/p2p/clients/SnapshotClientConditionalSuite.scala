package io.constellationnetwork.node.shared.http.p2p.clients

import cats.effect.{IO, Ref}

import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import org.http4s._
import weaver.SimpleIOSuite

/** Coverage for `SnapshotClient.handleConditionalResponse` — the pure 304-vs-200 branch logic of the new conditional combined-stream
  * fetcher. Exercises the response-status switch in isolation so we don't need to stand up a PeerAuth / http4s server in the test.
  *
  * Scope is deliberately narrow: the 304 branch is the NEW code path (it's the byte-saver), and the error branch is the safety net for
  * unexpected statuses. The 200 happy path delegates to the same `decodeCombinedBody` that the legacy `getLatest` already exercises in
  * production and integration tests, so we don't reproduce its coverage here.
  */
object SnapshotClientConditionalSuite extends SimpleIOSuite {

  // Stub types just to satisfy the type parameters of `handleConditionalResponse`. We never
  // construct an instance because the 304 branch returns Left without touching the decoder,
  // and the error branch raises before invoking it.
  private trait StubSnapshot extends Snapshot
  private trait StubSnapshotInfo extends SnapshotInfo[StateProof]

  test("304 NotModified → Left(NotModified) and decoder is never invoked") {
    val unreachableDecoder: Stream[IO, Byte] => IO[(Signed[StubSnapshot], StubSnapshotInfo)] =
      _ => IO.raiseError(new RuntimeException("decoder must not be called for 304"))
    for {
      callCount <- Ref[IO].of(0)
      countingDecoder: (Stream[IO, Byte] => IO[(Signed[StubSnapshot], StubSnapshotInfo)]) = stream =>
        callCount.update(_ + 1) >> unreachableDecoder(stream)
      resp = Response[IO](status = Status.NotModified)
      result <- SnapshotClient.handleConditionalResponse[IO, StubSnapshot, StubSnapshotInfo](resp, countingDecoder)
      decoderCalls <- callCount.get
    } yield
      expect(result == Left(SnapshotClient.NotModified), s"304 should produce Left(NotModified), got $result").and(
        expect(decoderCalls == 0, s"decoder must not be invoked on 304, but was called $decoderCalls times")
      )
  }

  test("non-200/304 status raises an error so the caller can surface a hard failure") {
    val unreachableDecoder: Stream[IO, Byte] => IO[(Signed[StubSnapshot], StubSnapshotInfo)] =
      _ => IO.raiseError(new RuntimeException("decoder must not be called when status is 5xx"))
    val resp = Response[IO](status = Status.InternalServerError)
    SnapshotClient
      .handleConditionalResponse[IO, StubSnapshot, StubSnapshotInfo](resp, unreachableDecoder)
      .attempt
      .map { attempted =>
        expect(attempted.isLeft, s"500 should raise, got $attempted").and(
          expect(
            attempted.swap.toOption.exists(_.getMessage.contains("Unexpected response status")),
            s"error should mention the unexpected status, got ${attempted.swap.toOption.map(_.getMessage)}"
          )
        )
      }
  }

  test("404 status also raises (only 200 + 304 are accepted)") {
    val unreachableDecoder: Stream[IO, Byte] => IO[(Signed[StubSnapshot], StubSnapshotInfo)] =
      _ => IO.raiseError(new RuntimeException("not called"))
    val resp = Response[IO](status = Status.NotFound)
    SnapshotClient
      .handleConditionalResponse[IO, StubSnapshot, StubSnapshotInfo](resp, unreachableDecoder)
      .attempt
      .map(attempted => expect(attempted.isLeft, s"404 should raise, got $attempted"))
  }
}
