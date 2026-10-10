package io.constellationnetwork.node.shared.infrastructure.gossip.p2p

import cats.data.NonEmptySet
import cats.effect.std.{Random, Supervisor}
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.node.shared.config.types.{GossipRoundConfig, GossipTimeoutsConfig, RumorStorageConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.http.p2p.headers.`X-Id`
import io.constellationnetwork.node.shared.http.p2p.middlewares.TimeoutMiddleware.{ResponseBodyIdleTimeout, ResponseBodyLifetimeTimeout}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, GossipRoundRunner, RumorStorage}
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
import fs2.{Chunk, Stream}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s._
import org.http4s.client.{Client, UnexpectedStatus}
import weaver.SimpleIOSuite

object GossipResponseDeadlineSuite extends SimpleIOSuite {
  private val id = PeerId(Hex("1" * 128))
  private val context = P2PContext(Host.fromString("127.0.0.1").get, Port.fromInt(9001).get, id)
  private val config = GossipTimeoutsConfig(30.seconds, 15.seconds, 60.seconds)
  private val rumor = Signed(
    PeerRumorRaw(id, Ordinal.MinValue, Json.fromString("test"), ContentType("test")),
    NonEmptySet.one(SignatureProof(id.toId, Signature(Hex("1" * 128))))
  )

  private val validSession = new Session[IO] {
    def createSession: IO[SessionToken] = IO.raiseError(new IllegalStateException("unused"))
    def verifyToken(peer: PeerId, token: Option[SessionToken]): IO[TokenVerificationResult] = IO.pure(TokenValid)
  }

  private def operations(client: GossipClient[IO]): List[IO[Unit]] =
    List(
      client.queryPeerRumors(PeerRumorInquiryRequest(Map.empty)).run(context).compile.drain,
      client.getInitialPeerRumors.run(context).compile.drain,
      client.getCommonRumorOffer.run(context).void,
      client.queryCommonRumors(QueryCommonRumorsRequest(Set.empty)).run(context).compile.drain,
      client.getInitialCommonRumorHashes.run(context).void
    )

  private def query(client: Client[IO], session: Session[IO] = validSession): Stream[IO, Signed[PeerRumorRaw]] =
    GossipClient.make(client, session, config).queryPeerRumors(PeerRumorInquiryRequest(Map.empty)).run(context)

  test("all five gossip response bodies time out when no data arrives and release their responses") {
    TestControl.executeEmbed {
      for {
        acquired <- Ref.of[IO, Int](0)
        released <- Ref.of[IO, Int](0)
        transport = Client[IO] { _ =>
          Resource.make(
            acquired.update(_ + 1).as(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(Stream.never[IO]))
          )(_ => released.update(_ + 1))
        }
        results <- operations(GossipClient.make(transport, validSession, config)).traverse(_.attempt)
        acquiredCount <- acquired.get
        releasedCount <- released.get
      } yield
        expect(results.forall(_.left.exists(_.isInstanceOf[ResponseBodyIdleTimeout]))) &&
          expect.same(acquiredCount, 5) && expect.same(releasedCount, 5)
    }
  }

  test("a decoded prefix advances before a later body stall times out") {
    TestControl.executeEmbed {
      for {
        emitted <- Ref.of[IO, Int](0)
        released <- Ref.of[IO, Int](0)
        body = Stream.chunk(Chunk.array((rumor.asJson.noSpaces + "\n").getBytes("UTF-8"))) ++ Stream.never[IO]
        transport = Client[IO] { _ =>
          Resource.make(IO.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body)))(_ => released.update(_ + 1))
        }
        result <- query(transport).evalTap(_ => emitted.update(_ + 1)).compile.drain.attempt
        count <- emitted.get
        releasedCount <- released.get
      } yield
        expect(result.left.exists(_.isInstanceOf[ResponseBodyIdleTimeout])) &&
          expect.same(count, 1) && expect.same(releasedCount, 1)
    }
  }

  test("a response may exceed fifteen seconds while body chunks keep arriving") {
    TestControl.executeEmbed {
      val bytes = rumor.asJson.noSpaces.getBytes("UTF-8")
      val chunkSize = (bytes.length + 2) / 3
      val body = Stream
        .emits(bytes.grouped(chunkSize).toList)
        .covary[IO]
        .flatMap(bytes => Stream.sleep_[IO](10.seconds) ++ Stream.chunk(Chunk.array(bytes)))

      for {
        transport <- IO.pure(Client[IO](_ => Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body))))
        start <- IO.monotonic
        result <- query(transport).compile.toList
        elapsed <- IO.monotonic.map(_ - start)
      } yield expect.same(result, List(rumor)) && expect(elapsed > config.client) && expect(elapsed < config.response)
    }
  }

  test("a continuously trickled incomplete body cannot hold a gossip worker forever") {
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
        releasedCount <- released.get
      } yield
        expect(result.left.exists(_.isInstanceOf[ResponseBodyLifetimeTimeout])) &&
          expect.same(elapsed, config.response) && expect(received > 100) && expect.same(releasedCount, 1)
    }
  }

  test("invalid session responses fail instead of reporting an empty successful round") {
    val invalidSession = new Session[IO] {
      def createSession: IO[SessionToken] = IO.raiseError(new IllegalStateException("unused"))
      def verifyToken(peer: PeerId, token: Option[SessionToken]): IO[TokenVerificationResult] = IO.pure(TokenDoesntMatch)
    }

    for {
      reads <- Ref.of[IO, Int](0)
      transport = Client[IO] { _ =>
        Resource.pure(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(Stream.eval(reads.update(_ + 1)).drain))
      }
      results <- operations(GossipClient.make(transport, invalidSession, config)).traverse(_.attempt)
      bodyReads <- reads.get
    } yield expect(results.forall(_.left.exists(_.isInstanceOf[UnexpectedStatus]))) && expect.same(bodyReads, 0)
  }

  test("a stalled recurring peer round keeps its decoded progress and frees its worker") {
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
            acquired <- Ref.of[IO, Int](0)
            processed <- Ref.of[IO, Int](0)
            healthchecks <- Ref.of[IO, Int](0)
            cluster <- ClusterStorage.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), Map(peer.id -> peer))
            body = Stream.chunk(Chunk.array((rumor.asJson.noSpaces + "\n").getBytes("UTF-8"))) ++ Stream.never[IO]
            transport = Client[IO] { _ =>
              Resource.eval(acquired.update(_ + 1)).as(Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(body))
            }
            health = new LocalHealthcheck[IO] {
              def start(p: Peer): IO[Unit] = healthchecks.update(_ + 1)
              def cancel(peerId: PeerId): IO[Unit] = IO.unit
            }
            runner <- GossipRoundRunner.make[IO](
              cluster,
              health,
              _ => query(transport).evalTap(_ => processed.update(_ + 1)).compile.drain,
              "peer",
              GossipRoundConfig(1, 200.millis, 1)
            )
            _ <- runner.runForever
            _ <- IO.sleep(16.seconds)
            acquisitionCount <- acquired.get
            processedCount <- processed.get
            healthcheckCount <- healthchecks.get
          } yield expect(acquisitionCount >= 2) && expect(processedCount >= 2) && expect.same(healthcheckCount, 1)
        }
      }
    }
  }

  test("a partial timed-out daemon round advances the cursor in the next request") {
    TestControl.executeEmbed {
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
          storage <- RumorStorage.make[IO](RumorStorageConfig(50L, 20L, 50L))
          requests <- Ref.of[IO, List[PeerRumorInquiryRequest]](List.empty)
          transport = Client[IO] { request =>
            Resource.eval {
              request.bodyText.compile.string
                .flatMap(body => IO.fromEither(decode[PeerRumorInquiryRequest](body)))
                .flatMap { inquiry =>
                  requests.modify { previous =>
                    val responseBody =
                      if (previous.isEmpty)
                        Stream.chunk(Chunk.array((rumor.asJson.noSpaces + "\n").getBytes("UTF-8"))) ++ Stream.never[IO]
                      else
                        Stream.empty
                    (previous :+ inquiry, Response[IO]().putHeaders(`X-Id`(id)).withBodyStream(responseBody))
                  }
                }
            }
          }
          client = GossipClient.make(transport, validSession, config)
          roundCfg = GossipRoundConfig(1, 200.millis, 1)
          first <- GossipDaemon
            .runPeerRound(storage, client, peer, roundCfg)(
              storage.addPeerRumorIfConsecutive(_).void
            )
            .attempt
          second <- GossipDaemon
            .runPeerRound(storage, client, peer, roundCfg)(
              storage.addPeerRumorIfConsecutive(_).void
            )
            .attempt
          recorded <- requests.get
          nextOrdinal = Ordinal(rumor.ordinal.generation, rumor.ordinal.counter.next)
        } yield
          expect(first.left.exists(_.isInstanceOf[ResponseBodyIdleTimeout])) &&
            expect(second.isRight) &&
            expect.same(recorded.map(_.ordinals), List(Map.empty, Map(id -> nextOrdinal)))
      }
    }
  }

  test("caller cancellation releases an acquired response before its idle deadline") {
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
}
