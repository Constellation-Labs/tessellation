package io.constellationnetwork.node.shared.infrastructure.mempool

import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.GlobalStateFieldId.Balances
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.mpt.PartitionNamespace.{AddressNamespace, EmptyNamespace, HypergraphNamespace}
import io.constellationnetwork.security.Hashed

import weaver.SimpleIOSuite

object EventMempoolSuite extends SimpleIOSuite {

  // Test event type
  case class TestEvent(id: String, data: String)

  // Simple test implementations
  class TestStateKeyExtractor extends StateKeyExtractor[IO, TestEvent, GlobalStateKey] {
    override def extractKeys(event: TestEvent): IO[Set[GlobalStateKey]] = {
      val addr = Address.fromBytes(event.id.getBytes)
      Set(
        GlobalStateKey(HypergraphNamespace, Balances, EmptyNamespace, AddressNamespace(addr))
      ).pure[IO]
    }
  }

  val testConfig: MempoolConfig = MempoolConfig(
    maxSize = 100,
    maxEventAge = 5.minutes
  )

  test("mempool config should have correct default values") {
    IO {
      expect.all(
        testConfig.maxSize == 100,
        testConfig.maxEventAge == 5.minutes
      )
    }
  }

  test("MempoolSnapshot.empty should return empty snapshot") {
    IO {
      val snapshot = MempoolSnapshot.empty[TestEvent, GlobalStateKey]
      expect.all(
        snapshot.entries.isEmpty,
        snapshot.hashes.isEmpty,
        snapshot.events.isEmpty,
        snapshot.size == 0
      )
    }
  }

  test("MempoolEntry.isExpired should correctly detect expired entries") {
    import java.time.Instant

    val now = Instant.now()
    val oldTime = now.minusMillis(10000)
    val recentTime = now.minusMillis(100)

    val mockHashed = null.asInstanceOf[Hashed[TestEvent]] // Placeholder for test
    val oldEntry = MempoolEntry(mockHashed, Set.empty[GlobalStateKey], oldTime)
    val recentEntry = MempoolEntry(mockHashed, Set.empty[GlobalStateKey], recentTime)

    for {
      oldExpired <- MempoolEntry.isExpired[IO, TestEvent, GlobalStateKey](oldEntry, 5000)
      recentExpired <- MempoolEntry.isExpired[IO, TestEvent, GlobalStateKey](recentEntry, 5000)
    } yield
      expect.all(
        oldExpired,
        !recentExpired
      )
  }
}
