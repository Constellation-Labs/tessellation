package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.effect.std.{Random, Supervisor}
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{GossipRoundConfig, GossipTimeoutsConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.http.p2p.headers.`X-Id`
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.p2p.GossipClient
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.schema.cluster._
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.gossip.PeerRumorInquiryRequest
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId, Responsive}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import fs2.Stream
import org.http4s.Response
import org.http4s.client.Client
import weaver.SimpleIOSuite

/** Stock reproduction, with real runner/client and a controlled response-body fixture.
  * Session verification is a stub: this is not authentication or native-network qualification.
  */
object StockGossipSlotStarvationSuite extends SimpleIOSuite {
  private implicit val metrics: Metrics[IO] = NoOpMetrics.make
  private val session = new Session[IO] {
    def createSession: IO[SessionToken] = IO.raiseError(new IllegalStateException("unused"))
    def verifyToken(peer: PeerId, token: Option[SessionToken]): IO[TokenVerificationResult] = IO.pure(TokenValid)
  }
  private def peer(n: Int): Peer = Peer(
    PeerId(Hex(f"$n%0128x")), Host.fromString("127.0.0.1").get,
    Port.fromInt(9000 + n).get, Port.fromInt(10000 + n).get,
    ClusterSessionToken(Generation.MinValue), SessionToken(Generation.MinValue),
    NodeState.Ready, Responsive, Hash.empty
  )

  test("stock five-second acquisition timeout does not bound a stalled acquired gossip body") {
    TestControl.executeEmbed {
      for {
        releases <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().putHeaders(`X-Id`(peer(1).id))
            .withBodyStream(Stream.never[IO])))(_ => releases.update(_ + 1))
        }
        client = GossipClient.make(transport, session, GossipTimeoutsConfig(10.seconds, 5.seconds))
        finished <- Ref.of[IO, Boolean](false)
        fiber <- client.queryPeerRumors(PeerRumorInquiryRequest(Map.empty)).run(peer(1)).compile.drain
          .guarantee(finished.set(true)).start
        _ <- IO.sleep(180.seconds)
        completed <- finished.get
        before <- releases.get
        _ <- fiber.cancel
        after <- releases.get
      } yield expect(!completed) && expect.same(before, 0) && expect.same(after, 1)
    }
  }

  test("eight stalled gossip bodies occupy all stock peer-round slots and starve a healthy queued peer for 180 seconds") {
    TestControl.executeEmbed {
      Supervisor[IO].use { implicit supervisor =>
        Random.scalaUtilRandomSeedInt[IO](0).flatMap { implicit random =>
          for {
            started <- Ref.of[IO, Set[Int]](Set.empty)
            released <- Ref.of[IO, Int](0)
            successes <- Ref.of[IO, Int](0)
            healthchecks <- Ref.of[IO, Int](0)
            bad = (1 to 8).map(peer).toList
            healthy = peer(9)
            cluster <- ClusterStorage.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), bad.map(p => p.id -> p).toMap)
            transport = Client[IO] { req =>
              val n = req.uri.port.get - 10000
              val body = if (n == 9) Stream.empty.covary[IO] else Stream.never[IO]
              Resource.make(started.update(_ + n).as(Response[IO]().putHeaders(`X-Id`(peer(n).id)).withBodyStream(body)))(
                _ => released.update(_ + 1)
              )
            }
            client = GossipClient.make(transport, session, GossipTimeoutsConfig(10.seconds, 5.seconds))
            health = new LocalHealthcheck[IO] {
              def start(p: Peer): IO[Unit] = healthchecks.update(_ + 1)
              def cancel(id: PeerId): IO[Unit] = IO.unit
            }
            runner <- GossipRoundRunner.make[IO](cluster, health,
              p => client.queryPeerRumors(PeerRumorInquiryRequest(Map.empty)).run(p).compile.drain >> successes.update(_ + 1),
              "peer", GossipRoundConfig(1, 200.millis, 8))
            _ <- runner.runForever
            _ <- IO.sleep(3.seconds)
            initial <- started.get
            _ <- cluster.addPeer(healthy)
            _ <- IO.sleep(180.seconds)
            requests <- started.get
            completed <- successes.get
            errors <- healthchecks.get
            freed <- released.get
          } yield expect.same(initial, (1 to 8).toSet) && expect.same(requests, initial) &&
            expect.same(completed, 0) && expect.same(errors, 0) && expect.same(freed, 0)
        }
      }
    }
  }
}
