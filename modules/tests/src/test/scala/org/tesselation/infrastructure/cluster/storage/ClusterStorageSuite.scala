package org.tesselation.infrastructure.cluster.storage

import cats.effect.{IO, Ref}

import org.tesselation.generators.{peerGen, peersGen}
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.IpLiteralSyntax
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ClusterStorageSuite extends SimpleIOSuite with Checkers {
  test("getPeers returns an empty Set") {
    for {
      peers <- Ref[IO].of(Set.empty[Peer])
      cs = ClusterStorage.make[IO](peers)
      get <- cs.getPeers
    } yield expect.same(get, Set.empty[Peer])
  }

  test("getPeers returns provided peers") {
    forall(peersGen()) { peers =>
      for {
        ref <- Ref[IO].of(peers)
        cs = ClusterStorage.make[IO](ref)
        p <- cs.getPeers
      } yield expect.same(p, peers)
    }
  }

  test("addPeer updates provided ref") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set.empty[Peer])
        cs = ClusterStorage.make[IO](ref)
        _ <- cs.addPeer(peer)
        peers <- ref.get
      } yield expect.same(peers, Set(peer))
    }
  }

  test("hasPeerId returns true if peer with provided Id exists") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerId <- cs.hasPeerId(peer.id)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerId returns false if peer with provided Id does not exist") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerId <- cs.hasPeerId(PeerId("unknown"))
      } yield expect(!hasPeerId)
    }
  }

  test("hasPeerHostPort returns true if peer with provided host and port exists") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerId <- cs.hasPeerHostPort(peer.ip, peer.p2pPort)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerHostPort returns false if peer with provided host and port does not exist") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        cs = ClusterStorage.make[IO](ref)
        hasPeerHostPort <- cs.hasPeerHostPort(host"0.0.0.1", port"1")
      } yield expect(!hasPeerHostPort)
    }
  }
}
