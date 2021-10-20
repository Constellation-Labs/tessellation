package org.tesselation.infrastructure.cluster.storage

import cats.effect.{IO, Ref}

import org.tesselation.schema.cluster.{InternalTrustUpdate, InternalTrustUpdateBatch, TrustInfo}
import org.tesselation.schema.generators._
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.IpLiteralSyntax
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers


object ClusterStorageSuite extends SimpleIOSuite with Checkers {
  test("getPeers returns an empty Set") {
    for {
      peers <- Ref[IO].of(Set.empty[Peer])
      trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
      cs = ClusterStorage.make[IO](peers, trust)
      get <- cs.getPeers
    } yield expect.same(get, Set.empty[Peer])
  }

  test("getPeers returns provided peers") {
    forall(peersGen()) { peers =>
      for {
        ref <- Ref[IO].of(peers)
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = ClusterStorage.make[IO](ref, trust)
        p <- cs.getPeers
      } yield expect.same(p, peers)
    }
  }

  test("addPeer updates provided ref") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set.empty[Peer])
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = ClusterStorage.make[IO](ref, trust)
        _ <- cs.addPeer(peer)
        peers <- ref.get
      } yield expect.same(peers, Set(peer))
    }
  }

  test("hasPeerId returns true if peer with provided Id exists") {
    forall(peerGen) { peer =>
      for {
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        ref <- Ref[IO].of(Set(peer))
        cs = ClusterStorage.make[IO](ref, trust)
        hasPeerId <- cs.hasPeerId(peer.id)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerId returns false if peer with provided Id does not exist") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = ClusterStorage.make[IO](ref, trust)
        hasPeerId <- cs.hasPeerId(PeerId("unknown"))
      } yield expect(!hasPeerId)
    }
  }

  test("hasPeerHostPort returns true if peer with provided host and port exists") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = ClusterStorage.make[IO](ref, trust)
        hasPeerId <- cs.hasPeerHostPort(peer.ip, peer.p2pPort)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerHostPort returns false if peer with provided host and port does not exist") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = ClusterStorage.make[IO](ref, trust)
        hasPeerHostPort <- cs.hasPeerHostPort(host"0.0.0.1", port"1")
      } yield expect(!hasPeerHostPort)
    }
  }

  test("trust update is applied") {
    forall(peerGen) { peer =>
      for {
        ref <- Ref[IO].of(Set(peer))
        trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
        cs = ClusterStorage.make[IO](ref, trust)
        _ <- cs.updateTrust(
          InternalTrustUpdateBatch(Seq(InternalTrustUpdate(peer.id, 0.5)))
        )
        updatedTrust <- cs.getTrust()
      } yield expect(updatedTrust(peer.id).trustLabel.get == 0.5)
    }
  }
}
