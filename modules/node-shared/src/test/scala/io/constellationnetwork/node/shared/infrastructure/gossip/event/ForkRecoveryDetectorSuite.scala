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
}
