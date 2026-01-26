package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.IO

import scala.concurrent.duration._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Unit tests for EventGossipDaemon configuration and types. Full daemon testing requires ClusterStorage which is tested at integration
  * level.
  */
object EventGossipDaemonSuite extends SimpleIOSuite {

  def makePeerId(n: Int): PeerId = PeerId(Hex(s"peer$n".padTo(64, '0')))

  test("EventGossipConfig should have correct defaults") {
    IO {
      val config = EventGossipConfig()
      expect.all(
        config.meshDegree == 6,
        config.meshLow == 4,
        config.meshHigh == 12,
        config.heartbeatInterval == 1.second,
        config.gossipFactor == 3,
        config.publishTimeout == 5.seconds,
        config.fetchTimeout == 5.seconds,
        config.pullInterval == 2.seconds,
        config.maxConcurrentPulls == 3
      )
    }
  }

  test("EventGossipConfig should allow custom values") {
    IO {
      val config = EventGossipConfig(
        meshDegree = 10,
        meshLow = 6,
        meshHigh = 15,
        heartbeatInterval = 500.millis,
        gossipFactor = 5
      )
      expect.all(
        config.meshDegree == 10,
        config.meshLow == 6,
        config.meshHigh == 15,
        config.heartbeatInterval == 500.millis,
        config.gossipFactor == 5
      )
    }
  }

  test("MeshInfo should contain correct information") {
    IO {
      val info = MeshInfo(
        meshSize = 5,
        meshPeers = Set(makePeerId(1), makePeerId(2)),
        seenHashCount = 100
      )
      expect.all(
        info.meshSize == 5,
        info.meshPeers.size == 2,
        info.seenHashCount == 100
      )
    }
  }

  test("MeshInfo should handle empty mesh") {
    IO {
      val info = MeshInfo(
        meshSize = 0,
        meshPeers = Set.empty,
        seenHashCount = 0
      )
      expect.all(
        info.meshSize == 0,
        info.meshPeers.isEmpty,
        info.seenHashCount == 0
      )
    }
  }
}
