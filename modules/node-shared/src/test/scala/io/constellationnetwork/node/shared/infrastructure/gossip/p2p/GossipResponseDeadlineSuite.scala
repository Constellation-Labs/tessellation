package io.constellationnetwork.node.shared.infrastructure.gossip.p2p

import java.util.concurrent.TimeoutException

import cats.data.NonEmptySet
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
import io.constellationnetwork.node.shared.infrastructure.gossip.GossipRoundRunner
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.schema.cluster._
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.client.Client
import weaver.SimpleIOSuite

object GossipResponseDeadlineSuite extends SimpleIOSuite {
  private val id = PeerId(Hex("1" * 128))
  private val context = P2PContext(Host.fromString("127.0.0.1").get, Port.fromInt(9001).get, id)
  // Transport/decode fixtures only; signature and session authentication are not under test.
  private val session = new Session[IO] {
    def createSession: IO[SessionToken] = IO.raiseError(new IllegalStateException("unused"))
    def verifyToken(peer: PeerId, token: Option[SessionToken]): IO[TokenVerificationResult] = IO.pure(TokenValid)
  }
  private val config = GossipTimeoutsConfig(10.seconds, 5.seconds)
  private val rumor = Signed(
    PeerRumorRaw(id, Ordinal.MinValue, Json.fromString("test"), ContentType("test")),
    NonEmptySet.one(SignatureProof(id.toId, Signature(Hex("1" * 128))))
  )
  private def query(client: Client[IO]): Stream[IO, Signed[PeerRumorRaw]] =
    GossipClient.make(client, session, config).queryPeerRumors(PeerRumorInquiryRequest(Map.empty)).run(context)

  test("acquisition and unfinished body share one deadline and release the response exactly once") {
    TestControl.executeEmbed {
      for {
        acquired <- Ref.of[IO, Int](0)
        released <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.eval(IO.sleep(4.seconds)) >> Resource.make(
            acquired.update(_ + 1).as(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(Stream.never[IO]))
          )(_ => released.update(_ + 1))
        }
        start <- IO.monotonic
        result <- query(transport).compile.drain.attempt
        elapsed <- IO.monotonic.map(_ - start)
        count <- acquired.get
        freed <- released.get
      } yield
        expect(result.left.exists(_.isInstanceOf[TimeoutException])) &&
          expect.same(elapsed, 5.seconds) && expect.same(count, 1) && expect.same(freed, 1)
    }
  }

  test("continually trickled bytes do not reset the total deadline and the response is released") {
    TestControl.executeEmbed {
      for {
        chunks <- Ref.of[IO, Int](0)
        released <- Ref.of[IO, Int](0)
        body = Stream.repeatEval(IO.sleep(100.millis) >> chunks.update(_ + 1).as(32.toByte))
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body)))(_ => released.update(_ + 1))
        }
        start <- IO.monotonic
        result <- query(transport).compile.drain.attempt
        elapsed <- IO.monotonic.map(_ - start)
        received <- chunks.get
        freed <- released.get
      } yield
        expect(result.left.exists(_.isInstanceOf[TimeoutException])) &&
          expect.same(elapsed, 5.seconds) && expect(received > 1) && expect.same(freed, 1)
    }
  }

  test("a decoded prefix is not emitted when the response body never completes") {
    TestControl.executeEmbed {
      for {
        emitted <- Ref.of[IO, Int](0)
        body = Stream.emits((rumor.asJson.noSpaces + "\n").getBytes("UTF-8")).covary[IO] ++ Stream.never[IO]
        transport = Client[IO](_ => Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body)))
        result <- query(transport).evalTap(_ => emitted.update(_ + 1)).compile.drain.attempt
        count <- emitted.get
      } yield expect(result.left.exists(_.isInstanceOf[TimeoutException])) && expect.same(count, 0)
    }
  }

  test("complete decoded response is released before downstream work exceeding five seconds") {
    TestControl.executeEmbed {
      for {
        released <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().putHeaders(`X-Id`(id)).withEntity(rumor.asJson.noSpaces)))(_ => released.update(_ + 1))
        }
        start <- IO.monotonic
        result <- query(transport).evalMap(r => released.get.flatMap(n => IO.sleep(6.seconds).as((r, n)))).compile.toList
        elapsed <- IO.monotonic.map(_ - start)
        count <- released.get
      } yield expect.same(result, List((rumor, 1))) && expect.same(elapsed, 6.seconds) && expect.same(count, 1)
    }
  }

  test("slow local processing completes through the real runner without a peer healthcheck") {
    TestControl.executeEmbed {
      implicit val metrics: Metrics[IO] = NoOpMetrics.make
      Supervisor[IO].use { implicit supervisor =>
        Random.scalaUtilRandomSeedInt[IO](0).flatMap { implicit random =>
          val peer = Peer(
            id,
            context.ip,
            Port.fromInt(9000).get,
            context.port,
            ClusterSessionToken(Generation.MinValue),
            SessionToken(Generation.MinValue),
            NodeState.Ready,
            Responsive,
            Hash.empty
          )
          for {
            completed <- Ref.of[IO, Int](0)
            healthchecks <- Ref.of[IO, Int](0)
            cluster <- ClusterStorage.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), Map(peer.id -> peer))
            transport = Client[IO](_ => Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withEntity(rumor.asJson.noSpaces)))
            health = new LocalHealthcheck[IO] {
              def start(p: Peer): IO[Unit] = healthchecks.update(_ + 1)
              def cancel(peerId: PeerId): IO[Unit] = IO.unit
            }
            runner <- GossipRoundRunner.make[IO](
              cluster,
              health,
              _ => query(transport).evalMap(_ => IO.sleep(6.seconds)).compile.drain >> completed.update(_ + 1),
              "peer",
              GossipRoundConfig(1, 200.millis, 1)
            )
            _ <- runner.runForever
            _ <- IO.sleep(7.seconds)
            successes <- completed.get
            errors <- healthchecks.get
          } yield expect.same(successes, 1) && expect.same(errors, 0)
        }
      }
    }
  }

  test("caller cancellation releases an acquired response before the deadline") {
    TestControl.executeEmbed {
      for {
        released <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(Stream.never[IO])))(_ => released.update(_ + 1))
        }
        fiber <- query(transport).compile.drain.start
        _ <- IO.sleep(1.second)
        _ <- fiber.cancel
        count <- released.get
      } yield expect.same(count, 1)
    }
  }

  test("malformed JSON or a decoded rumor with missing fields fails and releases the response") {
    List("not-json", "{}").traverse { body =>
      for {
        released <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().putHeaders(`X-Id`(id)).withEntity(body)))(_ => released.update(_ + 1))
        }
        result <- query(transport).compile.drain.attempt
        count <- released.get
      } yield expect(result.isLeft) && expect.same(count, 1)
    }.map(_.reduce(_ && _))
  }

  test("common queries, offers and both initialization calls retain their six-second body lifetime") {
    TestControl.executeEmbed {
      val transport = Client[IO] { req =>
        val content = req.uri.path.renderString match {
          case "/rumors/common/offer" => "{\"offer\":[]}"
          case "/rumors/common/init"  => "{\"seen\":[]}"
          case _                      => ""
        }
        val body = Stream.sleep_[IO](6.seconds) ++ Stream.emits(content.getBytes("UTF-8")).covary[IO]
        Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body))
      }
      val client = GossipClient.make(transport, session, config)
      List(
        client.queryCommonRumors(QueryCommonRumorsRequest(Set.empty)).run(context).compile.drain,
        client.getCommonRumorOffer.run(context).void,
        client.getInitialPeerRumors.run(context).compile.drain,
        client.getInitialCommonRumorHashes.run(context).void
      ).traverse(_.attempt).map(results => expect(results.forall(_.isRight)))
    }
  }
}
