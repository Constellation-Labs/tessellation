package io.constellationnetwork.node.shared.infrastructure.gossip.p2p

import java.util.concurrent.TimeoutException

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.GossipTimeoutsConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.headers.`X-Id`
import io.constellationnetwork.schema.cluster._
import io.constellationnetwork.schema.gossip.{PeerRumorInquiryRequest, QueryCommonRumorsRequest}
import io.constellationnetwork.schema.peer.{P2PContext, PeerId}
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import weaver.SimpleIOSuite

object GossipResponseDeadlineSuite extends SimpleIOSuite {
  private val id = PeerId(Hex("1" * 128))
  private val context = P2PContext(Host.fromString("127.0.0.1").get, Port.fromInt(9001).get, id)
  private val session = new Session[IO] {
    def createSession: IO[SessionToken] = IO.raiseError(new IllegalStateException("unused"))
    def verifyToken(peer: PeerId, token: Option[SessionToken]): IO[TokenVerificationResult] = IO.pure(TokenValid)
  }
  private val config = GossipTimeoutsConfig(10.seconds, 5.seconds)
  private val names = List("peer query", "common query", "common offer")
  private def operations(client: GossipClient[IO], peer: P2PContext = context): List[IO[Unit]] = List(
    client.queryPeerRumors(PeerRumorInquiryRequest(Map.empty)).run(peer).compile.drain,
    client.queryCommonRumors(QueryCommonRumorsRequest(Set.empty)).run(peer).compile.drain,
    client.getCommonRumorOffer.run(peer).void
  )
  private def isTimeout(result: Either[Throwable, Unit]): Boolean = result.left.exists(_.isInstanceOf[TimeoutException])

  test("initialization body lifetime is unchanged: a healthy six-second body still completes") {
    TestControl.executeEmbed {
      val transport = Client[IO] { req =>
        val content = if (req.uri.path.renderString == "/rumors/common/init") "{\"seen\":[]}" else ""
        val body = Stream.sleep_[IO](6.seconds) ++ Stream.emits(content.getBytes("UTF-8")).covary[IO]
        Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body))
      }
      val client = GossipClient.make(transport, session, config)
      (client.getInitialPeerRumors.run(context).compile.drain, client.getInitialCommonRumorHashes.run(context).void).tupled.attempt.map(
        result => expect(result.isRight)
      )
    }
  }

  test("invalid session responses never expose their body to the gossip decoder") {
    val invalidSession = new Session[IO] {
      def createSession: IO[SessionToken] = IO.raiseError(new IllegalStateException("unused"))
      def verifyToken(peer: PeerId, token: Option[SessionToken]): IO[TokenVerificationResult] = IO.pure(TokenDoesntMatch)
    }
    for {
      reads <- Ref.of[IO, Int](0)
      releases <- Ref.of[IO, Int](0)
      transport = Client[IO] { _ =>
        Resource.make(
          IO.pure(
            Response[IO]()
              .putHeaders(`X-Id`(id))
              .withBodyStream(Stream.eval(reads.update(_ + 1)).drain)
          )
        )(_ => releases.update(_ + 1))
      }
      _ <- operations(GossipClient.make(transport, invalidSession, config)).traverse(_.attempt)
      read <- reads.get
      released <- releases.get
    } yield expect.same(read, 0) && expect.same(released, 3)
  }

  test("malformed complete bodies fail rather than being accepted as valid responses") {
    val transport = Client[IO] { _ =>
      Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withEntity("not-json"))
    }
    operations(GossipClient.make(transport, session, config))
      .traverse(_.attempt)
      .map(results => expect(results.forall(_.isLeft)))
  }

  names.zipWithIndex.foreach {
    case (name, index) =>
      test(s"$name: one five-second deadline includes acquisition, body and decoder; release once, no replay") {
        TestControl.executeEmbed {
          for {
            acquired <- Ref.of[IO, Int](0)
            released <- Ref.of[IO, Int](0)
            transport = Client[IO] { _ =>
              Resource.eval(IO.sleep(4.seconds)) >> Resource.make(
                acquired
                  .update(_ + 1)
                  .as(
                    Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(Stream.never[IO])
                  )
              )(_ => released.update(_ + 1))
            }
            start <- IO.monotonic
            result <- operations(GossipClient.make(transport, session, config))(index).attempt
            elapsed <- IO.monotonic.map(_ - start)
            count <- acquired.get
            freed <- released.get
          } yield expect(isTimeout(result)) && expect.same(elapsed, 5.seconds) && expect.same(count, 1) && expect.same(freed, 1)
        }
      }
  }

  test("trickled incomplete JSON cannot reset the total deadline") {
    TestControl.executeEmbed {
      for {
        chunks <- Ref.of[IO, Int](0)
        body = Stream.repeatEval(IO.sleep(100.millis) >> chunks.update(_ + 1).as(32.toByte))
        transport = Client[IO](_ => Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body)))
        start <- IO.monotonic
        result <- operations(GossipClient.make(transport, session, config)).head.attempt
        elapsed <- IO.monotonic.map(_ - start)
        received <- chunks.get
      } yield expect(isTimeout(result)) && expect.same(elapsed, 5.seconds) && expect(received > 1)
    }
  }

  test("healthy empty peer response and complete common responses succeed before the deadline") {
    val transport = Client[IO] { req =>
      val body = req.uri.path.renderString match {
        case "/rumors/common/offer" => "{\"offer\":[]}"
        case "/rumors/common/init"  => "{\"seen\":[]}"
        case _                      => ""
      }
      Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withEntity(body))
    }
    operations(GossipClient.make(transport, session, config)).traverse(_.attempt).map(results => expect(results.forall(_.isRight)))
  }

  test("caller cancellation closes the response and does not wait for the deadline") {
    TestControl.executeEmbed {
      for {
        freed <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.make(
            IO.pure(
              Response[IO]()
                .putHeaders(`X-Id`(id))
                .withBodyStream(Stream.never[IO])
            )
          )(_ => freed.update(_ + 1))
        }
        fiber <- operations(GossipClient.make(transport, session, config)).head.start
        _ <- IO.sleep(1.second)
        _ <- fiber.cancel
        count <- freed.get
      } yield expect.same(count, 1)
    }
  }

  test("real Ember loopback: stock keeps consuming a trickled response beyond its deadline; corrected client fails") {
    val body = Stream.repeatEval(IO.sleep(20.millis).as(32.toByte))
    val app = HttpApp[IO](_ => IO.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body)))
    (
      EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        .withPort(Port.fromInt(0).get)
        .withHttpApp(app)
        .build,
      EmberClientBuilder.default[IO].build
    ).tupled.use {
      case (server, transport) =>
        val peer = context.copy(port = Port.fromInt(server.address.getPort).get)
        val limits = GossipTimeoutsConfig(5.seconds, 500.millis)
        for {
          completed <- Ref.of[IO, Boolean](false)
          fiber <- operations(StockGossipClient.make(transport, session, limits), peer).head.attempt
            .guarantee(completed.set(true))
            .start
          _ <- IO.sleep(2.seconds)
          stockFinished <- completed.get
          _ <- fiber.cancel
          result <- operations(GossipClient.make(transport, session, limits), peer).head.attempt.timeout(5.seconds)
        } yield expect(!stockFinished) && expect(isTimeout(result))
    }
  }
}
