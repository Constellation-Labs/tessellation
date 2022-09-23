package org.tessellation.sdk.infrastructure.healthcheck

import cats.data.Kleisli
import cats.effect.kernel.Fiber
import cats.effect.{IO, Temporal}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.parallel._

import scala.concurrent.duration._

import org.tessellation.schema.cluster.{ClusterId, SessionToken}
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.generators._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer._
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.clients.NodeClient
import org.tessellation.sdk.infrastructure.cluster.storage.ClusterStorage

import eu.timepit.refined.auto._
import io.chrisdavenport.mapref.MapRef
import retry.{RetryPolicies, RetryPolicy}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object LocalHealthcheckSuite extends SimpleIOSuite with Checkers {

  def mkPeersR = MapRef.ofConcurrentHashMap[IO, PeerId, IO[Fiber[IO, Throwable, Unit]]]()
  def retryPolicy: RetryPolicy[IO] = RetryPolicies.fibonacciBackoff[IO](2.seconds)
  def nodeClient = mkNodeClient(responsive = false)
  def mapPeer: Peer => Peer = _.copy(responsiveness = Responsive, state = NodeState.Ready, session = SessionToken(Generation.MinValue))

  test("does not spawn healthcheck for an unknown node") {

    val initialPeers: Map[PeerId, Peer] = Map.empty

    forall(peerGen) { peer =>
      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        lh.start(peer).flatMap { _ =>
          peersR.keys.map(_.size).map(expect.same(_, 0))
        }
      }
    }
  }

  test("does not spawn healthcheck for already unresponsive peer") {

    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> peer.copy(responsiveness = Unresponsive))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        lh.start(peer).flatMap { _ =>
          peersR.keys.map(_.size).map(expect.same(_, 0))
        }
      }
    }
  }

  test("spawns healthcheck for responsive peer") {

    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        lh.start(peer)
          .flatMap { _ =>
            peersR.keys.map(_.size).map(expect.same(_, 1))
          }
          .flatTap(_ => lh.cancel(peer.id))
      }
    }
  }

  test("spawns healthcheck for responsive peer and expect closed fiber") {

    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true), cs)

        lh.start(peer).flatMap(_ => Temporal[IO].sleep(2.seconds)).flatMap { _ =>
          peersR.keys.map(_.size).map(expect.same(_, 0))
        }
      }
    }
  }

  test("cancels existing healthcheck") {

    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        lh.start(peer).flatMap(_ => lh.cancel(peer.id)).flatMap { _ =>
          Temporal[IO].sleep(2.seconds) >> peersR(peer.id).get.map(expect.same(_, None))
        }
      }
    }
  }

  test("spawns healthcheck for many responsive peers") {

    forall(peersGen()) { peers =>
      val initialPeers: Map[PeerId, Peer] =
        peers.map(mapPeer).map(p => (p.id, p)).toMap

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        peers.toList.parTraverse { peer =>
          lh.start(peer)
        }.flatMap { _ =>
          Temporal[IO].sleep(2.seconds) >> peersR.keys.map(_.size).map(expect.same(_, peers.size))
        }.flatTap { _ =>
          peers.toList.parTraverse { peer =>
            lh.cancel(peer.id)
          }
        }
      }
    }
  }

  test("spawns healthcheck for many responsive peers and cancels all") {

    forall(peersGen()) { peers =>
      val initialPeers: Map[PeerId, Peer] =
        peers.map(mapPeer).map(p => (p.id, p)).toMap

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        peers.toList.parTraverse { peer =>
          lh.start(peer).flatMap(_ => lh.cancel(peer.id))
        }.flatMap { _ =>
          Temporal[IO].sleep(2.seconds) >> peersR.keys.map(_.size).map(expect.same(_, 0))
        }
      }
    }
  }

  test("spawns healthcheck for many responsive peers twice") {

    forall(peersGen()) { peers =>
      val initialPeers: Map[PeerId, Peer] =
        peers.map(mapPeer).map(p => (p.id, p)).toMap

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

        peers.toList.parTraverse { peer =>
          for {
            _ <- lh.start(peer)
            _ <- Temporal[IO].sleep(1.second)
            _ <- lh.cancel(peer.id)
            _ <- Temporal[IO].sleep(1.second)
            _ <- cs.setPeerResponsiveness(peer.id, Responsive)
            _ <- lh.start(peer)
            _ <- Temporal[IO].sleep(1.second)
          } yield ()
        }.flatMap { _ =>
          peersR.keys.map(_.size).map(expect.same(_, peers.size))
        }.flatTap { _ =>
          peers.toList.parTraverse { peer =>
            lh.cancel(peer.id)
          }
        }
      }
    }
  }

  def mkNodeClient(responsive: Boolean, session: Option[SessionToken] = None): NodeClient[IO] = new NodeClient[IO] {
    def getState: PeerResponse.PeerResponse[IO, NodeState] = ???

    def health: PeerResponse.PeerResponse[IO, Boolean] = ???

    def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] =
      Kleisli.apply { _ =>
        if (responsive)
          IO(session.orElse(Some(SessionToken(Generation.MinValue))))
        else
          IO.raiseError[Option[SessionToken]](new Throwable("unresponsive"))
      }
  }

  def mkClusterStorage(initialPeers: Map[PeerId, Peer] = Map.empty): IO[ClusterStorage[IO]] = {
    val id = ClusterId("d2547754-8aea-428b-a1aa-048e8b2d344b")
    ClusterStorage.make[IO](id, initialPeers)
  }
}
