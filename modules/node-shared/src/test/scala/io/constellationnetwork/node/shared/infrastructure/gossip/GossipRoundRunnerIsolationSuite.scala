package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.effect.std.{Random, Supervisor}
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.GossipRoundConfig
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId, Responsive}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object GossipRoundRunnerIsolationSuite extends SimpleIOSuite {
  private val host = Host.fromString("127.0.0.1").get

  private def peer(digit: Char, port: Int): Peer =
    Peer(
      PeerId(Hex(digit.toString * 128)),
      host,
      Port.fromInt(port).get,
      Port.fromInt(port + 1).get,
      ClusterSessionToken(Generation.MinValue),
      SessionToken(Generation.MinValue),
      NodeState.Ready,
      Responsive,
      Hash.empty
    )

  test("slow peers leave the healthy worker lane and cool down after consecutive failures") {
    TestControl.executeEmbed {
      implicit val metrics: Metrics[IO] = NoOpMetrics.make
      Supervisor[IO].use { implicit supervisor =>
        Random.scalaUtilRandomSeedInt[IO](0).flatMap { implicit random =>
          val slow = peer('1', 9000)
          val healthy = peer('2', 9010)
          val cfg = GossipRoundConfig(
            fanout = 2,
            interval = 100.millis,
            maxConcurrentRounds = 1,
            maxConcurrentSuspectRounds = 1,
            failureCountThreshold = 3,
            failureWindow = 5.seconds
          )

          for {
            cluster <- ClusterStorage
              .make[IO](ClusterId("1e41de41-ff5e-4cf2-8941-e103bbed2c0d"), Map(slow.id -> slow, healthy.id -> healthy))
            slowActive <- Ref.of[IO, Boolean](false)
            slowAttempts <- Ref.of[IO, Int](0)
            healthyAttempts <- Ref.of[IO, Int](0)
            healthyWhileSlow <- Ref.of[IO, Int](0)
            healthchecks <- Ref.of[IO, Int](0)
            health = new LocalHealthcheck[IO] {
              def start(peer: Peer): IO[Unit] = healthchecks.update(_ + 1)
              def cancel(peerId: PeerId): IO[Unit] = IO.unit
            }
            round = (selected: Peer) =>
              if (selected.id === slow.id)
                (slowAttempts.update(_ + 1) >> slowActive.set(true) >> IO.sleep(10.seconds) >>
                  IO.raiseError[Unit](new RuntimeException("slow peer exceeded its response budget")))
                  .guarantee(slowActive.set(false))
              else
                healthyAttempts.update(_ + 1) >> slowActive.get.flatMap(active => healthyWhileSlow.update(_ + 1).whenA(active))
            runner <- GossipRoundRunner.make[IO](cluster, health, round, "peer", cfg)
            _ <- runner.runForever
            _ <- IO.sleep(32.seconds)
            slowCount <- slowAttempts.get
            healthyCount <- healthyAttempts.get
            concurrentHealthyCount <- healthyWhileSlow.get
            healthcheckCount <- healthchecks.get
          } yield
            expect.same(slowCount, 3) &&
              expect(healthyCount > 0) &&
              expect(concurrentHealthyCount > 0) &&
              expect.same(healthcheckCount, 3)
        }
      }
    }
  }
}
