package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.effect.IO

import org.http4s._
import weaver.SimpleIOSuite

/** Coverage for `PeerAuthMiddleware.isLargeStreamRoute`, the predicate that gates the heavy-route bypass on both the server-side response
  * signer and the client-side response verifier. The bypass is what stops the middleware from buffering the entire combined-snapshot
  * response body into a `Vector[Chunk[Byte]]` per concurrent download (the root cause of the multi-GB transient heap pressure observed on
  * recovery).
  *
  * Scope: the predicate is a pure URI inspection so we exercise it directly. The end-to-end "no buffering happens" assertion would require
  * standing up an actual http4s client/server pair; that is out of scope for the unit suite. The downstream guarantee here is structural:
  * any route the predicate accepts will skip the body-buffering path in both directions, and only paths matching the layer prefixes
  * (`global-snapshots` and `snapshots`) plus the heavyweight suffixes will be accepted.
  */
object PeerAuthMiddlewareLargeStreamSuite extends SimpleIOSuite {

  private def reqAt(path: String): Request[IO] =
    Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path))

  test("global-snapshots combined-stream route matches (GL0 wiring)") {
    IO.pure(
      expect(PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/latest/combined/stream")))
    )
  }

  test("snapshots combined-stream route matches (CL0 wiring)") {
    IO.pure(
      expect(PeerAuthMiddleware.isLargeStreamRoute(reqAt("/snapshots/latest/combined/stream")))
    )
  }

  test("per-ordinal checkpoint route matches with numeric ordinal") {
    IO.pure(
      expect(PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/latest/combined/checkpoint/12345")))
    )
  }

  test("per-ordinal checkpoint route matches under cl0 prefix") {
    IO.pure(
      expect(PeerAuthMiddleware.isLargeStreamRoute(reqAt("/snapshots/latest/combined/checkpoint/7")))
    )
  }

  test("checkpoint/info bypass: info probe is cheap and must NOT match the heavy predicate") {
    // The `/checkpoint/info` route returns a tiny JSON descriptor; clients use it to ETag-check
    // before fetching the body. It must keep the existing peer-auth verification path because
    // there is no embedded `Signed[S]` payload to vouch for transport authenticity on its own.
    IO.pure(
      expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/latest/combined/checkpoint/info")))
    )
  }

  test("plain latest-combined (non-stream) route does NOT match -- it's the cached small variant") {
    // `/latest/combined` is the in-memory cached response, served as a strict byte-array entity.
    // The verifier already tolerates that single allocation; the heap-pressure regression came
    // from the streaming variant which holds the bytes for the duration of the slow drain.
    IO.pure(
      expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/latest/combined")))
    )
  }

  test("latest/metadata probe does NOT match -- small JSON keeps signed verification") {
    IO.pure(
      expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/latest/metadata")))
    )
  }

  test("latest/ordinal probe does NOT match -- small JSON keeps signed verification") {
    IO.pure(
      expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/latest/ordinal")))
    )
  }

  test("unrelated cluster/registration route does NOT match") {
    IO.pure(
      expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/cluster/info"))).and(
        expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/registration/register")))
      )
    )
  }

  test("per-hash and per-ordinal snapshot reads do NOT match -- those go via small entity decoders") {
    // Single-snapshot reads (`/<ordinal>` and `/<hash>`) return `Signed[S]` directly, not the
    // `[snapshot, state]` tuple. Their wire size is bounded and the per-entity codec path already
    // produces a manageable allocation; bypassing the verifier would lose body-level transport
    // authenticity for no heap win. Keep them on the signed path.
    IO.pure(
      expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/12345"))).and(
        expect(!PeerAuthMiddleware.isLargeStreamRoute(reqAt("/global-snapshots/abc123def456")))
      )
    )
  }
}
