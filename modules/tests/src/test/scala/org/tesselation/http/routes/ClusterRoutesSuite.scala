package org.tesselation.http.routes

import cats.effect.IO

import org.tesselation.domain.cluster.{Cluster, ClusterStorage}
import org.tesselation.generators.peersGen
import org.tesselation.infrastructure.cluster.Cluster
import org.tesselation.schema.cluster.PeerToJoin
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}
import org.http4s.Method._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite

object ClusterRoutesSuite extends HttpSuite {

  test("GET peers succeeds") {
    val peers = peersGen()

    forall(peers) {
      case peers =>
        val clusterStorage = new TestClusterStorage {
          override def getPeers: IO[Set[Peer]] = IO.pure(peers)
        }
        val cluster = new TestCluster()

        val req = GET(uri"/cluster/peers")
        val routes = ClusterRoutes(clusterStorage, cluster).cliRoutes

        expectHttpBodyAndStatus(routes, req)(peers, Status.Ok)
    }
  }

  test("POST join fails when peer id already in use") {
    val peers = peersGen()

    forall(peers) {
      case peers =>
        val clusterStorage = new TestClusterStorage {
          override def hasPeerId(id: PeerId): IO[Boolean] = IO.pure(true)
        }
        val cluster = Cluster.make[F](clusterStorage)

        val req = POST(uri"/cluster/join").withEntity(peers.head)
        val routes = ClusterRoutes(clusterStorage, cluster).cliRoutes

        expectHttpStatus(routes, req)(Status.Conflict)
    }
  }

  test("POST join fails when peer host and port already in use") {
    val peers = peersGen()

    forall(peers) {
      case peers =>
        val clusterStorage = new TestClusterStorage {
          override def hasPeerHostPort(host: Host, p2pPort: Port): IO[Boolean] = IO.pure(true)
        }
        val cluster = Cluster.make[F](clusterStorage)

        val req = POST(uri"/cluster/join").withEntity(peers.head)
        val routes = ClusterRoutes(clusterStorage, cluster).cliRoutes

        expectHttpStatus(routes, req)(Status.Conflict)
    }
  }
}

protected class TestClusterStorage extends ClusterStorage[IO] {
  override def getPeers: IO[Set[Peer]] = IO.pure(Set.empty)

  override def addPeer(peer: Peer): IO[Unit] = IO.unit

  override def hasPeerId(id: PeerId): IO[Boolean] = IO.pure(false)

  override def hasPeerHostPort(host: Host, p2pPort: Port): IO[Boolean] = IO.pure(false)
}

protected class TestCluster extends Cluster[IO] {
  override def join(toPeer: PeerToJoin): IO[Unit] = IO.unit
}
