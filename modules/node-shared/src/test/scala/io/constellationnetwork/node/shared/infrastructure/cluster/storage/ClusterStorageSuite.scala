package io.constellationnetwork.node.shared.infrastructure.cluster.storage

import cats.effect.IO
import cats.syntax.option._

import io.constellationnetwork.schema.cluster.{ClusterId, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.IpLiteralSyntax
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
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

  test("removePeerIfSession removes the record only when the recorded session still matches") {
    forall(peerGen) { generated =>
      val peer = generated.copy(session = SessionToken(Generation(PosLong.unsafeFrom(1L))))
      val newer = peer.copy(session = SessionToken(Generation(PosLong.unsafeFrom(2L))))
      for {
        cs <- ClusterStorage.make[IO](clusterId, Map(peer.id -> peer))
        mismatch <- cs.removePeerIfSession(peer.id, newer.session)
        stillThere <- cs.getPeer(peer.id)
        _ <- cs.addPeer(newer)
        staleCompare <- cs.removePeerIfSession(peer.id, peer.session)
        newerKept <- cs.getPeer(peer.id)
        matched <- cs.removePeerIfSession(peer.id, newer.session)
        gone <- cs.getPeer(peer.id)
        absent <- cs.removePeerIfSession(peer.id, newer.session)
      } yield
        expect(!mismatch, "a non-matching expected session removes nothing")
          .and(expect(stillThere.contains(peer), "the record is untouched after a mismatch"))
          .and(expect(!staleCompare, "comparing against the superseded session removes nothing"))
          .and(expect(newerKept.contains(newer), "the concurrently installed newer session survives"))
          .and(expect(matched, "the matching session removes the record"))
          .and(expect(gone.isEmpty, "the record is gone"))
          .and(expect(!absent, "removing an absent peer reports false"))
    }
  }

  test("setPeerResponsivenessIfSession relabels the record only when the recorded session still matches") {
    forall(peerGen) { generated =>
      val peer = generated.copy(session = SessionToken(Generation(PosLong.unsafeFrom(1L))), responsiveness = Unresponsive)
      val newer = peer.copy(session = SessionToken(Generation(PosLong.unsafeFrom(2L))), responsiveness = Unresponsive)
      for {
        cs <- ClusterStorage.make[IO](clusterId, Map(peer.id -> peer))
        mismatch <- cs.setPeerResponsivenessIfSession(peer.id, newer.session, Responsive)
        untouched <- cs.getPeer(peer.id)
        matched <- cs.setPeerResponsivenessIfSession(peer.id, peer.session, Responsive)
        restored <- cs.getPeer(peer.id)
        _ <- cs.addPeer(newer)
        staleCompare <- cs.setPeerResponsivenessIfSession(peer.id, peer.session, Responsive)
        newerKept <- cs.getPeer(peer.id)
        absent <- cs.setPeerResponsivenessIfSession(PeerId(Hex("ab" * 64)), peer.session, Responsive)
      } yield
        expect(!mismatch, "a non-matching expected session relabels nothing")
          .and(expect(untouched.contains(peer), "the record is untouched after a mismatch"))
          .and(expect(matched, "the matching session relabels the record"))
          .and(expect(restored.exists(_.responsiveness == Responsive), "the record is Responsive afterwards"))
          .and(expect(!staleCompare, "comparing against the superseded session relabels nothing"))
          .and(expect(newerKept.contains(newer), "the concurrently installed newer session keeps its own responsiveness"))
          .and(expect(!absent, "relabelling an absent peer reports false"))
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
