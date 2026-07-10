package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object ForkRecoveryDetectorSuite extends SimpleIOSuite {

  def makePeerId(n: Int): PeerId = PeerId(Hex(s"peer$n".padTo(64, '0')))
  def ordinal(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)
  def chainTip(ord: Long, hash: String): ChainTip = ChainTip(ordinal(ord), Hash(hash))
  def localTip(ord: Long, hash: String): IO[Option[ChainTip]] = ChainTip(ordinal(ord), Hash(hash)).some.pure[IO]

  val testConfig: MeshState.MeshConfig = MeshState.MeshConfig(
    targetMeshSize = 6,
    minMeshSize = 2,
    maxMeshSize = 10,
    minScore = -10.0,
    scoreDecay = 0.9,
    staleThresholdMs = 60000
  )

  test("detects fork when local ordinal lags behind majority") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      majorityHash = Hash("majority-hash")

      _ <- (1 to 5).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(21), majorityHash))
      }

      detector = ForkRecoveryDetector.make[IO](mesh, localTip(10, "local-hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield
      expect(result.isDefined, "Should detect fork divergence")
        .and(expect(result.get.majorityOrdinal == ordinal(21), "Majority ordinal should be 21"))
        .and(expect(result.get.majorityHash == majorityHash, "Majority hash should match"))
        .and(expect(result.get.lag == 11L, s"Lag should be 11, got ${result.get.lag}"))
        .and(expect(result.get.majorityPeers.size == 5, s"Should have 5 majority peers, got ${result.get.majorityPeers.size}"))
  }

  test("does not detect fork when lag is below threshold") {
    for {
      mesh <- MeshState.make[IO](testConfig)

      _ <- (1 to 5).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(20), Hash("hash")))
      }

      detector = ForkRecoveryDetector.make[IO](mesh, localTip(18, "hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Should not detect fork when lag (2) is below threshold (5)")
  }

  test("does not detect fork when no majority exists") {
    for {
      mesh <- MeshState.make[IO](testConfig)

      // 3 peers at ordinal 20, 3 peers at ordinal 15 — no clear majority (3/6 = 50%, not > 50%)
      _ <- (1 to 3).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(20), Hash("hash-20")))
      }
      _ <- (4 to 6).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(15), Hash("hash-15")))
      }

      detector = ForkRecoveryDetector.make[IO](mesh, localTip(10, "local-hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Should not detect fork when no ordinal has > 50% majority")
  }

  test("does not detect fork when local chain tip is unknown") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      _ <- (1 to 5).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(20), Hash("hash")))
      }
      detector = ForkRecoveryDetector.make[IO](mesh, none[ChainTip].pure[IO], forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Should not detect fork when local chain tip is unknown")
  }

  test("does not detect fork with no chain tips") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      detector = ForkRecoveryDetector.make[IO](mesh, localTip(10, "hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Should not detect fork with empty chain tips")
  }

  test("identifies correct majority peers") {
    for {
      mesh <- MeshState.make[IO](testConfig)

      // 4 peers at ordinal 21, 1 peer at ordinal 15
      _ <- (1 to 4).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(21), Hash("hash-21")))
      }
      _ <- mesh.updateChainTip(makePeerId(5), ChainTip(ordinal(15), Hash("hash-15")))

      detector = ForkRecoveryDetector.make[IO](mesh, localTip(10, "local-hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
      expectedPeers = (1 to 4).map(makePeerId).toSet
    } yield expect(result.isDefined).and(expect(result.get.majorityPeers == expectedPeers, "Majority should be peers 1-4"))
  }

  test("detects running fork — same ordinal, different hash") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      majorityHash = Hash("canonical-hash")
      localHash = Hash("forked-hash")

      // 4 peers at ordinal 10 with canonical hash
      _ <- (1 to 4).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(10), majorityHash))
      }
      // 1 peer at ordinal 10 with same forked hash as local
      _ <- mesh.updateChainTip(makePeerId(5), ChainTip(ordinal(10), localHash))

      detector = ForkRecoveryDetector.make[IO](mesh, localTip(10, "forked-hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield
      expect(result.isDefined, "Should detect running fork (same ordinal, different hash)")
        .and(expect(result.get.majorityHash == majorityHash, "Should identify canonical hash"))
  }

  test("no running fork when local hash matches majority") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      sharedHash = Hash("same-hash")

      _ <- (1 to 5).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(10), sharedHash))
      }

      detector = ForkRecoveryDetector.make[IO](mesh, localTip(10, "same-hash"), forkLagThreshold = 10)
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Should not detect fork when local hash matches majority")
  }

  // ─── Tier 2: Quorum probe verification ──────────────────────────────────

  /** Build a probe that returns the given hash for any (peer, ordinal) request. */
  def stubProbe(respondWith: Hash): HashAtOrdinalProbe[IO] = new HashAtOrdinalProbe[IO] {
    def probe(peerId: PeerId, ordinal: SnapshotOrdinal): IO[Option[Hash]] =
      respondWith.some.pure[IO]
  }

  /** Build a probe that returns different hashes per peer, keyed by peerId. */
  def mappedProbe(responses: Map[PeerId, Option[Hash]]): HashAtOrdinalProbe[IO] =
    new HashAtOrdinalProbe[IO] {
      def probe(peerId: PeerId, ordinal: SnapshotOrdinal): IO[Option[Hash]] =
        responses.getOrElse(peerId, none[Hash]).pure[IO]
    }

  test("tier 2: probe returns match → no fork (lagging on canonical chain)") {
    // Local at ord 8, majority peers at ord 10 with hashY. Lag=2 (below threshold=10).
    // Probe returns local's hash at ord 8 → we're on same chain, just lagging.
    val localHash = Hash("local-hash-at-8")
    for {
      mesh <- MeshState.make[IO](testConfig)
      _ <- (1 to 3).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(10), Hash("majority-hash-at-10")))
      }

      detector = ForkRecoveryDetector.make[IO](
        mesh,
        localTip(8, "local-hash-at-8"),
        forkLagThreshold = 10,
        verifyHashAt = Some(stubProbe(localHash))
      )
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Probe confirmed same chain — should not flag as fork")
  }

  test("tier 2: probe returns mismatch → fork detected") {
    // Local at ord 8 hash=X, majority peers at ord 10. Probe returns hash=Y at ord 8
    // from majority peers — different from our X, so we're on a fork.
    for {
      mesh <- MeshState.make[IO](testConfig)
      _ <- (1 to 3).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(10), Hash("majority-tip-hash")))
      }

      detector = ForkRecoveryDetector.make[IO](
        mesh,
        localTip(8, "our-fork-hash"),
        forkLagThreshold = 10,
        verifyHashAt = Some(stubProbe(Hash("canonical-hash-at-8")))
      )
      result <- detector.detectForkDivergence
    } yield
      expect(result.isDefined, "Probe detected hash divergence at our ordinal — should flag")
        .and(expect(result.get.majorityOrdinal == ordinal(10), "Majority ordinal propagated"))
        .and(expect(result.get.lag == 2L, s"Lag should be 2, got ${result.get.lag}"))
  }

  test("tier 2: all probes return None → inconclusive → no fork") {
    // Peers are in majority but don't have our ordinal in history (None).
    // We can't verify — don't act.
    for {
      mesh <- MeshState.make[IO](testConfig)
      _ <- (1 to 3).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(10), Hash("majority-tip")))
      }

      probe = new HashAtOrdinalProbe[IO] {
        def probe(peerId: PeerId, ordinal: SnapshotOrdinal): IO[Option[Hash]] = none[Hash].pure[IO]
      }

      detector = ForkRecoveryDetector.make[IO](
        mesh,
        localTip(8, "local-hash"),
        forkLagThreshold = 10,
        verifyHashAt = Some(probe)
      )
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Probe inconclusive — should NOT flag as fork")
  }

  test("tier 2: mixed probe responses without quorum → no fork") {
    // 3 peers probed: one matches, one mismatches, one absent. No clear majority either way.
    val p1 = makePeerId(1)
    val p2 = makePeerId(2)
    val p3 = makePeerId(3)
    for {
      mesh <- MeshState.make[IO](testConfig)
      _ <- List(p1, p2, p3).traverse_ { pid =>
        mesh.updateChainTip(pid, ChainTip(ordinal(10), Hash("majority-tip")))
      }

      probe = mappedProbe(
        Map(
          p1 -> Hash("local-hash").some,
          p2 -> Hash("divergent-hash").some,
          p3 -> none[Hash]
        )
      )

      detector = ForkRecoveryDetector.make[IO](
        mesh,
        localTip(8, "local-hash"),
        forkLagThreshold = 10,
        verifyHashAt = Some(probe)
      )
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "No quorum match or mismatch — should NOT flag as fork")
  }

  test("tier 2: probe not wired → falls back to previous behavior (no detection on small lag)") {
    // Without probe, small lag on same hash behaves exactly as before — no detection.
    for {
      mesh <- MeshState.make[IO](testConfig)
      _ <- (1 to 3).toList.traverse_ { i =>
        mesh.updateChainTip(makePeerId(i), ChainTip(ordinal(10), Hash("majority")))
      }

      detector = ForkRecoveryDetector.make[IO](
        mesh,
        localTip(8, "local"),
        forkLagThreshold = 10,
        verifyHashAt = None
      )
      result <- detector.detectForkDivergence
    } yield expect(result.isEmpty, "Without probe, behavior must match pre-tier-2 semantics")
  }
}
