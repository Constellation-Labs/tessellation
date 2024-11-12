package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.std.{Queue, Supervisor}
import cats.effect.{IO, Resource}

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.block.generators.signedBlockGen
import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon
import io.constellationnetwork.node.shared.snapshot.currency.{BlockEvent, CurrencySnapshotArtifact, CurrencySnapshotEvent}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.gossip.{Counter, Ordinal, RumorRaw}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.Stream
import io.circe.{Encoder, Json}
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object SnapshotEventsPublisherDaemonSuite extends MutableIOSuite with Checkers {

  override type Res =
    (Supervisor[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], Metrics[IO], Encoder[CurrencySnapshotEvent], KeyPair)

  override def sharedResource: Resource[IO, Res] =
    for {
      s <- Supervisor[IO](await = false)
      implicit0(js: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].toResource
      implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
      h = Hasher.forJson[IO]
      m = Metrics.noopIO
      e = {
        implicit val noopEncoder: Encoder[DataUpdate] = new Encoder[DataUpdate] {
          final def apply(a: DataUpdate): Json = Json.Null
        }
        CurrencySnapshotEvent.encoder
      }
      kp <- KeyPairGenerator.makeKeyPair[IO].toResource
    } yield (s, js, h, sp, m, e, kp)

  test("test gossip deduplcation") { res =>

    implicit val (s, js, h, sp, m, e, keyPair) = res

    val blockEvent1 = signedBlockGen.sample.map(BlockEvent(_)).get
    val blockEvent2 = signedBlockGen.sample.map(BlockEvent(_)).get

    val selfId = PeerId(Hex("0000000000000000"))

    val consensusConfig = ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = NonNegLong(10),
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(
        maxBinarySizeBytes = PosInt(5000000)
      )
    )

    for {
      consensusStorage <- ConsensusStorage.make[
        IO,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](consensusConfig)

      _ <- consensusStorage.addEvents(Map(selfId -> List((Ordinal(Generation(PosLong(1)), Counter(PosLong(1))), blockEvent1))))

      rumorQueue <- Queue.unbounded[IO, Hashed[RumorRaw]]
      rumors = Stream.fromQueueUnterminated(rumorQueue)

      l1OutputQueue <- Queue.unbounded[IO, Option[CurrencySnapshotEvent]]
      _ <- l1OutputQueue.offer(Some(blockEvent1))
      _ <- l1OutputQueue.offer(Some(blockEvent1))
      _ <- l1OutputQueue.offer(Some(blockEvent2))
      _ <- l1OutputQueue.offer(None)

      events = Stream.fromQueueNoneTerminated(l1OutputQueue)
      gossip <- Gossip.make[IO](rumorQueue, selfId, Generation(PosLong(1)), keyPair)
      daemon = SnapshotEventsPublisherDaemon.make(gossip, events, consensusStorage).spawn
      _ <- daemon.start
      count <- rumors.map { r =>
        println(r)
        r
      }.compile.count
      _ = println(count)
    } yield {
      println("...")
      println(count)
      success
    }
  }
}
