package org.tessellation.sdk.domain.cluster.programs

import cats.data.{Ior, Kleisli}
import cats.effect.IO

import org.tessellation.schema.generators._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.security.hex.Hex
import org.tessellation.schema.{cluster, node, peer}
import org.tessellation.sdk.domain.cluster.programs.PeerDiscovery
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.clients.ClusterClient

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

  def mkPeerDiscovery(peersInCluster: Set[Peer], discoverdPeers: Set[Peer]) = {

    val clusterClient = new ClusterClient[IO] {

      override def getPeers: PeerResponse.PeerResponse[IO, Set[Peer]] = ???

      override def getDiscoveryPeers: PeerResponse.PeerResponse[IO, Set[Peer]] = Kleisli.apply { _ =>
        IO(discoverdPeers)
      }

    }

    val clusterStorage = new ClusterStorage[IO] {

      override def getPeers: IO[Set[Peer]] = IO(peersInCluster)

      override def getResponsivePeers: IO[Set[Peer]] = IO(peersInCluster)

      override def getPeer(id: PeerId): IO[Option[Peer]] = ???

      override def addPeer(peer: Peer): IO[Unit] = ???

      override def hasPeerId(id: PeerId): IO[Boolean] = ???

      override def hasPeerHostPort(host: Host, p2pPort: Port): IO[Boolean] = ???

      override def setPeerState(id: PeerId, state: node.NodeState): IO[Unit] = ???

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

  test("discoverFrom - only unknown peers should be returned") {
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
          res <- peerDiscovery.discoverFrom(discoverFromPeer)
        } yield expect.same(res, newPeers)
    }
  }

  test("discoverFrom - no new peers should be returned when all has been seen already") {
    forall { (commonPeers: Set[Peer], newPeers: Set[Peer], knownPeers: Set[Peer]) =>
      val peersInCluster = commonPeers ++ knownPeers
      val returnedPeers = commonPeers ++ newPeers
      for {
        peerDiscovery <- mkPeerDiscovery(peersInCluster, returnedPeers)
        _ <- peerDiscovery.discoverFrom(peerGen.sample.get)
        res <- peerDiscovery.discoverFrom(peerGen.sample.get)
      } yield expect.same(res, Set.empty)
    }
  }

  test("getPeers - only peers not saved in cluster should be cached") {
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
          _ <- peerDiscovery.discoverFrom(discoverFromPeer)
          _ <- peerDiscovery.discoverFrom(discoverFromPeer)
          res <- peerDiscovery.getPeers
        } yield expect.same(res, newPeers)
    }
  }

}
