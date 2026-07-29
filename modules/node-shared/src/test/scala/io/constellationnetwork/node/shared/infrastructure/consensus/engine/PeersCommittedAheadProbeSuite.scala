package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** Effectful coverage for the HTTP preflight core (issue #1533): metadata corroboration, sampling bounds, tolerant per-peer failure
  * handling, at-or-above ordinal comparison, and the never-confirm-on-degraded timeout posture. The pure decision composition is covered by
  * `AbandonmentEscalationSignalSuite`; this suite pins the probe boundary itself.
  */
object PeersCommittedAheadProbeSuite extends SimpleIOSuite with Checkers {

  private val key: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(100L)

  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)
  private def metadata(n: Long, hash: String = "snapshot"): SnapshotMetadata =
    SnapshotMetadata(ord(n), Hash(hash), Hash("parent"))

  private val hexChars = "0123456789abcdef"

  private def readyPeers(base: Peer, n: Int): List[Peer] =
    List.tabulate(n) { i =>
      base.copy(id = PeerId(Hex(hexChars(i % 16).toString * 128)), state = NodeState.Ready)
    }

  private def withRandom[A](run: Random[IO] => IO[A]): IO[A] =
    Random.scalaUtilRandom[IO].flatMap { implicit r =>
      run(r)
    }

  test("empty ready set completes with nothing asked and nothing confirmed") {
    withRandom { implicit r =>
      PeersCommittedAheadProbe
        .probe[IO](Nil, (_: Peer) => IO.raiseError(new Exception("must not be called")), key)
        .map { result =>
          expect(!result.confirmedAhead)
            .and(expect.same(AbandonmentTracker.ProbeOutcome.Completed, result.outcome))
            .and(expect.same(0, result.probedPeers))
        }
    }
  }

  test("all fetches failing yields probed-but-unresponded and never confirms") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 3)
        PeersCommittedAheadProbe
          .probe[IO](peers, (_: Peer) => IO.raiseError[SnapshotMetadata](new Exception("503")), key)
          .map { result =>
            expect(!result.confirmedAhead, "a probe with zero responders must not confirm")
              .and(expect.same(3, result.probedPeers))
              .and(expect.same(0, result.respondedPeers))
          }
      }
    }
  }

  test("one responder at the key cannot confirm despite the rest failing") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 4)
        val healthy = peers.head.id
        val fetch: Peer => IO[SnapshotMetadata] =
          p => if (p.id === healthy) IO.pure(metadata(100L)) else IO.raiseError(new Exception("503"))
        PeersCommittedAheadProbe
          .probe[IO](peers, fetch, key)
          .map { result =>
            expect(!result.confirmedAhead, "one peer cannot authorize a recovery transition").and(expect.same(1, result.respondedPeers))
          }
      }
    }
  }

  test("all responders strictly below the key never confirm (the cluster-wide stall answer)") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 3)
        PeersCommittedAheadProbe
          .probe[IO](peers, (_: Peer) => IO.pure(metadata(99L)), key)
          .map { result =>
            expect(!result.confirmedAhead, "previous-ordinal answers everywhere = nobody produced the key")
              .and(expect.same(3, result.respondedPeers))
          }
      }
    }
  }

  test("two matching responders at the key confirm when they are a strict majority") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 3)
        val minority = peers.last.id
        PeersCommittedAheadProbe
          .probe[IO](peers, p => IO.pure(if (p.id === minority) metadata(99L) else metadata(100L)), key)
          .map { result =>
            expect(result.confirmedAhead).and(expect.same(2, result.corroboratingPeers))
          }
      }
    }
  }

  test("ahead responders that disagree on the snapshot hash cannot confirm") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 3)
        val fetch: Peer => IO[SnapshotMetadata] = p => IO.pure(metadata(100L, p.id.value.value.take(8)))
        PeersCommittedAheadProbe
          .probe[IO](peers, fetch, key)
          .map { result =>
            expect(!result.confirmedAhead, "ordinal agreement without hash agreement is insufficient")
              .and(expect.same(1, result.corroboratingPeers))
          }
      }
    }
  }

  test("the sample is bounded: a large ready set probes at most sampleSize peers") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 12)
        PeersCommittedAheadProbe
          .probe[IO](peers, (_: Peer) => IO.pure(metadata(99L)), key)
          .map(result => expect.same(PeersCommittedAheadProbe.SampleSize, result.probedPeers))
      }
    }
  }

  test("per-peer timeout lets matching healthy peers behind a hung peer confirm") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 3)
        val hung = peers.head.id
        val fetch: Peer => IO[SnapshotMetadata] =
          p => if (p.id === hung) IO.never else IO.pure(metadata(100L))
        PeersCommittedAheadProbe
          .probe[IO](
            peers,
            fetch,
            key,
            parallelism = 1,
            perPeerTimeout = 20.millis,
            overallTimeout = 200.millis
          )
          .map { result =>
            expect(result.confirmedAhead, "a hung peer must not monopolize the only worker slot").and(expect.same(2, result.respondedPeers))
          }
      }
    }
  }

  test("an overall timeout has a distinct outcome and never confirms") {
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 2)
        PeersCommittedAheadProbe
          .probe[IO](
            peers,
            (_: Peer) => IO.never[SnapshotMetadata],
            key,
            perPeerTimeout = 1.second,
            overallTimeout = 50.millis
          )
          .map { result =>
            expect.same(AbandonmentTracker.ProbeOutcome.TimedOut, result.outcome).and(expect(!result.confirmedAhead))
          }
      }
    }
  }

  test("SMALL CLUSTER: the single peer of a two-node metagraph can confirm on its own") {
    // The corroboration requirement clamps to the sample size. A two-node metagraph's isolated
    // node has exactly one Ready peer; demanding two matching responses there would re-open
    // #1533 as a permanent small-cluster suppression. That single peer is also the only possible
    // download source, and downloads stay signature-validated regardless.
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 1)
        PeersCommittedAheadProbe
          .probe[IO](peers, (_: Peer) => IO.pure(metadata(101L)), key)
          .map { result =>
            expect(result.confirmedAhead, "the lone genuine peer must be able to unblock recovery")
              .and(expect.same(1, result.corroboratingPeers))
              .and(expect.same(1, result.probedPeers))
          }
      }
    }
  }

  test("SMALL CLUSTER: a two-peer sample still demands both when only one responds ahead") {
    // The clamp follows the SAMPLE, not the responders: with two peers asked, a single ahead
    // answer (the other failing) stays insufficient, so one faulty peer cannot confirm just
    // because its neighbor is down. The requirement only relaxes when the Ready set itself
    // shrinks (the dead peer leaves the responsive set on later abandonment cycles).
    forall(peerGen) { base =>
      withRandom { implicit r =>
        val peers = readyPeers(base, 2)
        val healthy = peers.head.id
        val fetch: Peer => IO[SnapshotMetadata] =
          p => if (p.id === healthy) IO.pure(metadata(101L)) else IO.raiseError(new Exception("503"))
        PeersCommittedAheadProbe
          .probe[IO](peers, fetch, key)
          .map { result =>
            expect(!result.confirmedAhead, "one answer of a two-peer sample is not corroboration")
              .and(expect.same(1, result.corroboratingPeers))
              .and(expect.same(2, result.probedPeers))
          }
      }
    }
  }
}
