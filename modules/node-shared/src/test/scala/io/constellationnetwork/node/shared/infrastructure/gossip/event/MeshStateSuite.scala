package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.IO

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object MeshStateSuite extends SimpleIOSuite {

  def makePeerId(n: Int): PeerId = PeerId(Hex(s"peer$n".padTo(64, '0')))

  val testConfig: MeshState.MeshConfig = MeshState.MeshConfig(
    targetMeshSize = 3,
    minMeshSize = 2,
    maxMeshSize = 5,
    minScore = -10.0,
    scoreDecay = 0.9,
    staleThresholdMs = 60000
  )

  test("MeshState should start empty") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peers <- mesh.getMeshPeers
      size <- mesh.meshSize
    } yield
      expect.all(
        peers.isEmpty,
        size == 0
      )
  }

  test("MeshState should graft peers into mesh") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      peer2 = makePeerId(2)
      result1 <- mesh.graft(peer1)
      result2 <- mesh.graft(peer2)
      meshPeers <- mesh.getMeshPeers
      size <- mesh.meshSize
    } yield
      expect.all(
        result1, // First graft succeeds
        result2, // Second graft succeeds
        meshPeers.contains(peer1),
        meshPeers.contains(peer2),
        size == 2
      )
  }

  test("MeshState should not graft already-grafted peers") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      result1 <- mesh.graft(peer1)
      result2 <- mesh.graft(peer1) // Same peer again
      size <- mesh.meshSize
    } yield
      expect.all(
        result1, // First graft succeeds
        !result2, // Second graft fails (already in mesh)
        size == 1
      )
  }

  test("MeshState should prune peers from mesh") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      _ <- mesh.graft(peer1)
      sizeBefore <- mesh.meshSize
      result <- mesh.prune(peer1)
      sizeAfter <- mesh.meshSize
    } yield
      expect.all(
        sizeBefore == 1,
        result, // Prune succeeds
        sizeAfter == 0
      )
  }

  test("MeshState should track peer state") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      _ <- mesh.addPeer(peer1)
      state <- mesh.getPeerState(peer1)
    } yield
      expect.all(
        state.isDefined,
        state.exists(_.peerId == peer1),
        state.exists(_.score == 0.0),
        state.exists(!_.inMesh)
      )
  }

  test("MeshState should record delivery and update score") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      _ <- mesh.graft(peer1)
      stateBefore <- mesh.getPeerState(peer1)
      _ <- mesh.recordDelivery(peer1)
      stateAfter <- mesh.getPeerState(peer1)
    } yield
      expect.all(
        stateBefore.exists(_.score == 0.0),
        stateAfter.exists(_.score == 1.0),
        stateAfter.exists(_.messagesDelivered == 1)
      )
  }

  test("MeshState should record failure and decrease score") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      _ <- mesh.graft(peer1)
      _ <- mesh.recordFailure(peer1)
      state <- mesh.getPeerState(peer1)
    } yield
      expect.all(
        state.exists(_.score == -0.5),
        state.exists(_.messagesFailed == 1)
      )
  }

  test("MeshState heartbeat should graft peers when below target") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      // Provide available peers but don't graft any yet
      // With adaptive mesh, effectiveTarget = min(availablePeers.size, maxMeshSize)
      availablePeers = Set(makePeerId(1), makePeerId(2), makePeerId(3), makePeerId(4))
      result <- mesh.heartbeat(availablePeers)
    } yield
      expect.all(
        result.grafted.size == availablePeers.size, // Should graft all available (4 < targetMeshSize)
        result.meshSize == availablePeers.size
      )
  }

  test("MeshState heartbeat should prune low-scoring peers") {
    for {
      mesh <- MeshState.make[IO](testConfig.copy(minScore = 0.0))
      peer1 = makePeerId(1)
      peer2 = makePeerId(2)
      _ <- mesh.graft(peer1)
      _ <- mesh.graft(peer2)
      // Make peer1 have negative score
      _ <- mesh.recordFailure(peer1)
      _ <- mesh.recordFailure(peer1)
      _ <- mesh.recordFailure(peer1)
      stateBefore <- mesh.getPeerState(peer1)
      availablePeers = Set(peer1, peer2, makePeerId(3))
      result <- mesh.heartbeat(availablePeers)
      stateAfter <- mesh.getPeerState(peer1)
    } yield
      expect.all(
        stateBefore.exists(_.score < 0),
        result.pruned.contains(peer1) || stateAfter.exists(!_.inMesh)
      )
  }

  test("MeshState heartbeat should apply score decay") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      _ <- mesh.graft(peer1)
      _ <- mesh.recordDelivery(peer1)
      _ <- mesh.recordDelivery(peer1)
      stateBefore <- mesh.getPeerState(peer1)
      _ <- mesh.heartbeat(Set(peer1))
      stateAfter <- mesh.getPeerState(peer1)
    } yield
      expect.all(
        stateBefore.exists(_.score == 2.0),
        stateAfter.exists(_.score == 2.0 * testConfig.scoreDecay)
      )
  }

  test("MeshState should remove peers") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      peer1 = makePeerId(1)
      _ <- mesh.graft(peer1)
      _ <- mesh.removePeer(peer1)
      state <- mesh.getPeerState(peer1)
      size <- mesh.meshSize
    } yield
      expect.all(
        state.isEmpty,
        size == 0
      )
  }

  test("MeshState should check if more peers needed") {
    for {
      mesh <- MeshState.make[IO](testConfig)
      needsMore1 <- mesh.needsMorePeers(3)
      peer1 = makePeerId(1)
      peer2 = makePeerId(2)
      peer3 = makePeerId(3)
      _ <- mesh.graft(peer1)
      _ <- mesh.graft(peer2)
      _ <- mesh.graft(peer3)
      needsMore2 <- mesh.needsMorePeers(3)
    } yield
      expect.all(
        needsMore1, // Empty mesh needs peers
        !needsMore2 // Full mesh doesn't need more
      )
  }

  test("PeerGossipState should track delivery correctly") {
    IO {
      val nowMs = System.currentTimeMillis()
      val state = PeerGossipState(makePeerId(1))
      val afterDelivery = state.recordDelivery(nowMs).recordDelivery(nowMs)
      expect.all(
        afterDelivery.messagesDelivered == 2,
        afterDelivery.score == 2.0
      )
    }
  }

  test("PeerGossipState should track failures correctly") {
    IO {
      val state = PeerGossipState(makePeerId(1))
      val afterFailure = state.recordFailure.recordFailure
      expect.all(
        afterFailure.messagesFailed == 2,
        afterFailure.score == -1.0
      )
    }
  }

  test("PeerGossipState should decay score correctly") {
    IO {
      val state = PeerGossipState(makePeerId(1), score = 10.0)
      val decayed = state.decayScore(0.9)
      expect(decayed.score == 9.0)
    }
  }
}
