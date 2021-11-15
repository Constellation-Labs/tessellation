package org.tessellation.infrastructure.cluster.storage

import cats.effect.IO
import cats.syntax.option._

import org.tessellation.schema.generators._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.security.hex.Hex

import com.comcast.ip4s.IpLiteralSyntax
import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ClusterStorageSuite extends SimpleIOSuite with Checkers {

  test("getPeers returns an empty Set") {
    for {
      peers <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](Map.empty)
      cs = ClusterStorage.make[IO](peers)
      get <- cs.getPeers
    } yield expect.same(get, Set.empty[Peer])
  }

  test("getPeers returns provided peers") {
    forall(peersGen()) { peers =>
      for {
        ref <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](peers.toList.map(p => p.id -> p).toMap)
        cs = ClusterStorage.make[IO](ref)
        p <- cs.getPeers
      } yield expect.same(p, peers)
    }
  }

  test("addPeer updates provided ref") {
    forall(peerGen) { peer =>
      for {
        ref <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](Map.empty)
        cs = ClusterStorage.make[IO](ref)
        _ <- cs.addPeer(peer)
        result <- ref(peer.id).get
      } yield expect.same(result, peer.some)
    }
  }

  test("hasPeerId returns true if peer with provided Id exists") {
    forall(peerGen) { peer =>
      for {
        ref <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](Map(peer.id -> peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerId <- cs.hasPeerId(peer.id)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerId returns false if peer with provided Id does not exist") {
    forall(peerGen) { peer =>
      for {
        ref <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](Map(peer.id -> peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerId <- cs.hasPeerId(PeerId(Hex("unknown")))
      } yield expect(!hasPeerId)
    }
  }

  test("hasPeerHostPort returns true if peer with provided host and port exists") {
    forall(peerGen) { peer =>
      for {
        ref <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](Map(peer.id -> peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerId <- cs.hasPeerHostPort(peer.ip, peer.p2pPort)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerHostPort returns false if peer with provided host and port does not exist") {
    forall(peerGen) { peer =>
      for {
        ref <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](Map(peer.id -> peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerHostPort <- cs.hasPeerHostPort(host"0.0.0.1", port"1")
      } yield expect(!hasPeerHostPort)
    }
  }
}
