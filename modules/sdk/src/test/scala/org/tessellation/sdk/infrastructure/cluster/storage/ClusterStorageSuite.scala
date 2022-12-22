package org.tessellation.sdk.infrastructure.cluster.storage

import cats.effect.IO
import cats.syntax.option._

import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.generators._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.security.hex.Hex

import com.comcast.ip4s.IpLiteralSyntax
import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ClusterStorageSuite extends SimpleIOSuite with Checkers {

  val clusterId = ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7")

  test("getPeers returns an empty Set") {
    for {
      cs <- ClusterStorage.make[IO](clusterId)
      get <- cs.getPeers
    } yield expect.same(get, Set.empty[Peer])
  }

  test("getPeers returns provided peers") {
    forall(peersGen()) { peers =>
      for {
        cs <- ClusterStorage.make[IO](clusterId, peers.toList.map(p => p.id -> p).toMap)
        p <- cs.getPeers
      } yield expect.same(p, peers)
    }
  }

  test("getPeer follows addPeer") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](clusterId)
        _ <- cs.addPeer(peer)
        result <- cs.getPeer(peer.id)
      } yield expect.same(result, peer.some)
    }
  }

  test("hasPeerId returns true if peer with provided Id exists") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](clusterId, Map(peer.id -> peer))
        hasPeerId <- cs.hasPeerId(peer.id)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerId returns false if peer with provided Id does not exist") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](clusterId, Map(peer.id -> peer))
        hasPeerId <- cs.hasPeerId(PeerId(Hex("unknown")))
      } yield expect(!hasPeerId)
    }
  }

  test("hasPeerHostPort returns true if peer with provided host and port exists") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](clusterId, Map(peer.id -> peer))
        hasPeerId <- cs.hasPeerHostPort(peer.ip, peer.p2pPort)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerHostPort returns false if peer with provided host and port does not exist") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](clusterId, Map(peer.id -> peer))
        hasPeerHostPort <- cs.hasPeerHostPort(host"0.0.0.1", port"1")
      } yield expect(!hasPeerHostPort)
    }
  }
}
