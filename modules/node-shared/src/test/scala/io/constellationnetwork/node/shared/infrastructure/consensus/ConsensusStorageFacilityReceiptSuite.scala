package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import monocle.Lens
import weaver.SimpleIOSuite

/** `ConsensusStorage.getFacilityReceipts`: a monotonic per-peer receipt of every accepted Facility, replacements included, unaffected by
  * round cleanup, and never counting a Facility the declaration window rejected.
  */
object ConsensusStorageFacilityReceiptSuite extends SimpleIOSuite {

  private final case class Outcome(key: SnapshotOrdinal, value: String)

  private implicit val outcomeEq: Eq[Outcome] = Eq.fromUniversalEquals
  private implicit val outcomeKey: Lens[Outcome, SnapshotOrdinal] =
    Lens[Outcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private type Storage = ConsensusStorage[IO, Unit, SnapshotOrdinal, Unit, Unit, String, Outcome, Unit]

  private val consensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 3L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(
        maxBinarySizeBytes = PosInt(1024),
        maxUpdateNodeParametersSize = PosInt(1024)
      )
    )

  private val peerA = PeerId(Hex("0a" * 64))
  private val peerB = PeerId(Hex("0b" * 64))
  private val key = SnapshotOrdinal.unsafeApply(100L)
  private val entropy = Hash.fromBytes("facility-receipts".getBytes("UTF-8"))

  private def storage: IO[Storage] =
    ConsensusStorage.make[IO, Unit, SnapshotOrdinal, Unit, Unit, String, Outcome, Unit](consensusConfig)

  private def facility(view: String): Facility =
    Facility(
      eventHashes = Set(Hash.fromBytes(view.getBytes("UTF-8"))),
      candidates = Candidates(Set.empty),
      trigger = EventTrigger.some,
      facilitatorsHash = entropy,
      lastGlobalSnapshotOrdinal = key,
      lastSnapshotHash = entropy,
      consensusConfigHash = entropy.some
    )

  test("every accepted Facility is a receipt, replacements from an already-counted peer included") {
    for {
      s <- storage
      _ <- s.addFacility(peerA, key, facility("view-0"))
      afterFirst <- s.getFacilityReceipts
      _ <- s.addFacility(peerA, key, facility("view-1"))
      declarations <- s.getResources(key).map(_.peerDeclarationsMap.count { case (_, d) => d.facility.isDefined })
      afterReplacement <- s.getFacilityReceipts
      _ <- s.addFacility(peerB, SnapshotOrdinal.unsafeApply(101L), facility("view-0"))
      afterOther <- s.getFacilityReceipts
    } yield
      expect.same(Map(peerA -> 1L), afterFirst) &&
        expect.same(1, declarations) &&
        expect(afterReplacement.get(peerA).contains(2L), s"a view-change replacement is a new receipt, got $afterReplacement") &&
        expect.same(Map(peerA -> 2L, peerB -> 1L), afterOther)
  }

  test("receipts survive round cleanup, so replenishment to the same declaration count still reads as a new arrival") {
    for {
      s <- storage
      _ <- s.addFacility(peerA, key, facility("view-0"))
      _ <- s.clearResources(key)
      afterCleanup <- s.getFacilityReceipts
      _ <- s.addFacility(peerA, key, facility("view-0"))
      afterReplenishment <- s.getFacilityReceipts
    } yield
      expect.same(Map(peerA -> 1L), afterCleanup) &&
        expect.same(Map(peerA -> 2L), afterReplenishment)
  }

  test("a Facility rejected by the declaration window is not a receipt") {
    val outsideWindow = SnapshotOrdinal.unsafeApply(key.value.value + consensusConfig.declarationRangeLimit + 1L)
    for {
      s <- storage
      _ <- s.trySetInitialConsensusOutcome(Outcome(key, "parent"))
      rejected <- s.addFacility(peerA, outsideWindow, facility("view-0"))
      receipts <- s.getFacilityReceipts
    } yield expect(rejected.isEmpty, "precondition: the declaration was rejected") && expect.same(Map.empty[PeerId, Long], receipts)
  }
}
