package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect._
import cats.effect.std.Semaphore
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import weaver.SimpleIOSuite

object ConsensusStorageTimeoutTest extends SimpleIOSuite {

  implicit val securityProvider: SecurityProvider[IO] = SecurityProvider.forAsync[IO]
  implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

  // Test types
  type Event = String
  type Key = Int
  type Artifact = String
  type Context = Unit
  type Status = Unit
  type Outcome = Int
  type Kind = String

  // Simple lens implementation for test
  implicit val keyLens: monocle.Lens[Outcome, Key] = monocle.Lens[Outcome, Key](identity)(k => _ => k)

  test("updatedAt should not be reset on resource updates") {
    val config = ConsensusConfig(
      timeTriggerInterval = 1.second,
      declarationTimeout = 5.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      peersDeclarationTimeout = 30.seconds,
      eventCutter = io.constellationnetwork.node.shared.config.types.EventCutterConfig(
        maxBinarySizeBytes = 1000000,
        maxUpdateNodeParametersSize = 1000
      )
    )

    for {
      storage <- ConsensusStorage.make[IO, Event, Key, Artifact, Context, Status, Outcome, Kind](config)
      peerId = PeerId("test-peer".refined)
      key = 1

      // Get initial resources
      initialResources <- storage.getResources(key)
      initialUpdatedAt = initialResources.updatedAt

      // Wait a bit
      _ <- IO.sleep(100.millis)

      // Add a facility (this should not update the timestamp)
      _ <- storage.addFacility(peerId, key, Facility("test-facility-hash".refined))

      // Get resources after update
      updatedResources <- storage.getResources(key)
      updatedAt = updatedResources.updatedAt

      // The timestamp should remain the same
      result = expect.same(initialUpdatedAt, updatedAt)
    } yield result
  }

  test("updatedAt should be set when resources are first created") {
    val config = ConsensusConfig(
      timeTriggerInterval = 1.second,
      declarationTimeout = 5.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      peersDeclarationTimeout = 30.seconds,
      eventCutter = io.constellationnetwork.node.shared.config.types.EventCutterConfig(
        maxBinarySizeBytes = 1000000,
        maxUpdateNodeParametersSize = 1000
      )
    )

    for {
      storage <- ConsensusStorage.make[IO, Event, Key, Artifact, Context, Status, Outcome, Kind](config)
      peerId = PeerId("test-peer".refined)
      key1 = 1
      key2 = 2

      // Add facility to key1
      _ <- storage.addFacility(peerId, key1, Facility("test-facility-hash".refined))
      resources1 <- storage.getResources(key1)

      // Wait a bit
      _ <- IO.sleep(100.millis)

      // Add facility to key2 (different key, so new resources)
      _ <- storage.addFacility(peerId, key2, Facility("test-facility-hash-2".refined))
      resources2 <- storage.getResources(key2)

      // The timestamps should be different (key2 should be later)
      result = expect(resources2.updatedAt > resources1.updatedAt)
    } yield result
  }
}
