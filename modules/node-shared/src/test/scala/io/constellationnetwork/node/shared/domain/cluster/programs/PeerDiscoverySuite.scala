package io.constellationnetwork.node.shared.domain.cluster.programs

import cats.data.{Ior, Kleisli}
import cats.effect.{Deferred, IO}
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.cluster.programs.PeerDiscovery
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.clients.ClusterClient
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.{cluster, node, peer}
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.{Host, Port}
import org.scalacheck.Arbitrary
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object PeerDiscoverySuite extends SimpleIOSuite with Checkers {

  private implicit val arbitraryPeer: Arbitrary[Peer] = Arbitrary(peerGen)

  val nodeId = PeerId(
    Hex(
      "6128e64d623ce4320c9523dc6d64d7d93647e40fb44c77d70bcb34dc4042e63cde16320f336c9c0011315aa9f006ad2941b9a92102a055e1bcc5a66ef8b612ef"
    )
  )

  lazy val selfPeer = peerGen.sample.get.copy(id = nodeId)

  def mkPeerDiscovery(peersInCluster: Set[Peer], discoverdPeers: Set[Peer]): IO[PeerDiscovery[IO]] =
    mkPeerDiscoveryAnswering(peersInCluster, Kleisli(_ => IO(discoverdPeers)))

  def mkPeerDiscoveryAnswering(
    peersInCluster: Set[Peer],
    answer: PeerResponse.PeerResponse[IO, Set[Peer]]
  ): IO[PeerDiscovery[IO]] = {

    val clusterClient = new ClusterClient[IO] {

      override def getPeers: PeerResponse.PeerResponse[IO, Set[Peer]] = ???

      override def getDiscoveryPeers: PeerResponse.PeerResponse[IO, Set[Peer]] = answer

    }

    val clusterStorage = new ClusterStorage[IO] {

      override def getPeers: IO[Set[Peer]] = IO(peersInCluster)

      override def getResponsivePeers: IO[Set[Peer]] = IO(peersInCluster)

      override def getPeer(id: PeerId): IO[Option[Peer]] = ???

      override def addPeer(peer: Peer): IO[Boolean] = ???

      override def hasPeerId(id: PeerId): IO[Boolean] = ???

      override def hasPeerHostPort(host: Host, p2pPort: Port): IO[Boolean] = ???

      override def updatePeerState(id: PeerId, state: node.NodeState): IO[Boolean] = ???

      override def removePeer(id: PeerId): IO[Unit] = ???

      override def removePeers(ids: Set[PeerId]): IO[Unit] = ???

      override def peerChanges: fs2.Stream[IO, Ior[Peer, Peer]] = ???

      override def setToken(token: cluster.ClusterSessionToken): IO[Unit] = ???

      override def getToken: IO[Option[cluster.ClusterSessionToken]] = ???

      override def getClusterId: cluster.ClusterId = ???

      override def createToken: IO[cluster.ClusterSessionToken] = ???

      override def setPeerResponsiveness(id: PeerId, responsiveness: peer.PeerResponsiveness): IO[Unit] = ???
    }

    PeerDiscovery.make(clusterClient, clusterStorage, nodeId)
  }

  test("discoverFrom - only unknown peers should be claimed") {
    forall {
      (
        discoverFromPeer: Peer,
        returnDiscoverFromPeer: Boolean,
        returnSelfPeer: Boolean,
        commonPeers: Set[Peer],
        newPeers: Set[Peer],
        knownPeers: Set[Peer]
      ) =>
        val peersInCluster = commonPeers ++ knownPeers
        val returnedPeers =
          commonPeers ++ newPeers ++ Set(discoverFromPeer).filter(_ => returnDiscoverFromPeer) ++ Set(selfPeer).filter(_ => returnSelfPeer)
        for {
          peerDiscovery <- mkPeerDiscovery(peersInCluster, returnedPeers)
          res <- peerDiscovery.discoverFrom(discoverFromPeer).use(claim => IO.pure(claim.peers))
        } yield expect.same(res, newPeers)
    }
  }

  test("discoverFrom - nothing is claimed while another holder owns every candidate") {
    forall { (commonPeers: Set[Peer], newPeers: Set[Peer], knownPeers: Set[Peer]) =>
      val peersInCluster = commonPeers ++ knownPeers
      val returnedPeers = commonPeers ++ newPeers
      for {
        peerDiscovery <- mkPeerDiscovery(peersInCluster, returnedPeers)
        res <- peerDiscovery
          .discoverFrom(peerGen.sample.get)
          .use(_ => peerDiscovery.discoverFrom(peerGen.sample.get).use(claim => IO.pure(claim.peers)))
      } yield expect.same(res, Set.empty)
    }
  }

  test("getPeers - only peers not saved in cluster should be queued, and only while a claim holds them") {
    forall {
      (
        discoverFromPeer: Peer,
        returnDiscoverFromPeer: Boolean,
        returnSelfPeer: Boolean,
        commonPeers: Set[Peer],
        newPeers: Set[Peer],
        knownPeers: Set[Peer]
      ) =>
        val peersInCluster = commonPeers ++ knownPeers
        val returnedPeers =
          commonPeers ++ newPeers ++ Set(discoverFromPeer).filter(_ => returnDiscoverFromPeer) ++ Set(selfPeer).filter(_ => returnSelfPeer)
        for {
          peerDiscovery <- mkPeerDiscovery(peersInCluster, returnedPeers)
          queued <- peerDiscovery
            .discoverFrom(discoverFromPeer)
            .use(_ => peerDiscovery.discoverFrom(discoverFromPeer).use(_ => peerDiscovery.getPeers))
          afterRelease <- peerDiscovery.getPeers
        } yield expect.same(queued, newPeers) && expect(afterRelease.isEmpty, s"released claims leave nothing queued, got $afterRelease")
    }
  }

  test("a peer is discoverable again once the claim that held it is released") {
    forall { (discoverFromPeer: Peer, candidate: Peer) =>
      val returnedPeers = Set(candidate)

      for {
        peerDiscovery <- mkPeerDiscovery(Set.empty, returnedPeers)
        probe <- peerDiscovery.discoverFrom(discoverFromPeer).use { first =>
          peerDiscovery.discoverFrom(discoverFromPeer).use(suppressed => IO.pure((first.peers, suppressed.peers)))
        }
        (first, suppressedWhileClaimed) = probe
        rediscovered <- peerDiscovery.discoverFrom(discoverFromPeer).use(claim => IO.pure(claim.peers))
      } yield
        expect.same(first, returnedPeers) &&
          expect(suppressedWhileClaimed.isEmpty) &&
          expect.same(rediscovered, returnedPeers)
    }
  }

  private val candidateA = peerGen.sample.get.copy(id = PeerId(Hex("11" * 64)))
  private val candidateB = peerGen.sample.get.copy(id = PeerId(Hex("22" * 64)))
  private val candidateC = peerGen.sample.get.copy(id = PeerId(Hex("55" * 64)))
  private val entryPeer = peerGen.sample.get.copy(id = PeerId(Hex("33" * 64)))
  private val concurrentPeer = peerGen.sample.get.copy(id = PeerId(Hex("44" * 64)))

  test("a release drops only the holder's own claims: overlapping claims keep the other holder's candidates queued") {
    val answers: PeerResponse.PeerResponse[IO, Set[Peer]] =
      Kleisli(p => IO.pure(if (p.id == entryPeer.id) Set(candidateA, candidateB) else Set(candidateA, candidateB, candidateC)))
    for {
      peerDiscovery <- mkPeerDiscoveryAnswering(Set.empty, answers)
      probe <- peerDiscovery.discoverFrom(entryPeer).allocated
      (entryClaim, releaseEntry) = probe
      afterSecond <- peerDiscovery.discoverFrom(concurrentPeer).use { secondClaim =>
        for {
          _ <- releaseEntry
          queuedAfterEntryRelease <- peerDiscovery.getPeers
        } yield (secondClaim.peers, queuedAfterEntryRelease)
      }
      (secondClaim, queuedAfterEntryRelease) = afterSecond
      queuedAfterBoth <- peerDiscovery.getPeers
    } yield
      expect.same(Set(candidateA, candidateB), entryClaim.peers) &&
        expect.same(Set(candidateC), secondClaim) &&
        expect.same(Set(candidateC), queuedAfterEntryRelease) &&
        expect(queuedAfterBoth.isEmpty, s"the second holder's release leaves nothing queued, got $queuedAfterBoth")
  }

  test("cancelling the discovery request interrupts it and claims nothing") {
    for {
      requested <- Deferred[IO, Unit]
      interrupted <- Deferred[IO, Unit]
      peerDiscovery <- mkPeerDiscoveryAnswering(
        Set.empty,
        Kleisli(_ => requested.complete(()).void >> IO.never.onCancel(interrupted.complete(()).void).as(Set(candidateA)))
      )
      holder <- peerDiscovery.discoverFrom(entryPeer).use(_ => IO.never).start
      _ <- requested.get
      _ <- holder.cancel
      wasInterrupted <- interrupted.tryGet
      queued <- peerDiscovery.getPeers
    } yield
      expect(wasInterrupted.isDefined, "the network request is not masked from cancellation") &&
        expect(queued.isEmpty, s"nothing is claimed by an interrupted request, got $queued")
  }

  test("a claim committed at the handoff is released by its own finalizer when the holder is cancelled") {
    for {
      respond <- Deferred[IO, Unit]
      bodyStarted <- Deferred[IO, Unit]
      peerDiscovery <- mkPeerDiscoveryAnswering(Set.empty, Kleisli(_ => respond.get.as(Set(candidateA, candidateB))))
      holder <- peerDiscovery.discoverFrom(entryPeer).use(_ => bodyStarted.complete(()).void >> IO.never).start
      _ <- respond.complete(())
      _ <- bodyStarted.get
      queuedWhileHeld <- peerDiscovery.getPeers
      _ <- holder.cancel
      queuedAfter <- peerDiscovery.getPeers
    } yield
      expect.same(Set(candidateA, candidateB), queuedWhileHeld) &&
        expect(queuedAfter.isEmpty, s"the cancelled holder's claim is released, got $queuedAfter")
  }

  test("a cancellation racing the discovery response never strands a claim") {
    (1 to 25).toList.traverse_ { _ =>
      for {
        respond <- Deferred[IO, Unit]
        peerDiscovery <- mkPeerDiscoveryAnswering(Set.empty, Kleisli(_ => respond.get.as(Set(candidateA, candidateB))))
        holder <- peerDiscovery.discoverFrom(entryPeer).use(_ => IO.never).start
        _ <- IO.both(respond.complete(()), holder.cancel)
        queued <- peerDiscovery.getPeers
        _ <- IO.raiseError(new AssertionError(s"claim stranded after a racing cancellation: $queued")).whenA(queued.nonEmpty)
      } yield ()
    }.as(success)
  }

}
