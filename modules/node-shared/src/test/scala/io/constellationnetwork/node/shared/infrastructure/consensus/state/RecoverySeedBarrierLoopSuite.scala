package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.Eq
import cats.data.NonEmptySet
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.SimpleIOSuite

object RecoverySeedBarrierLoopSuite extends SimpleIOSuite {

  private final case class Observation(aligned: Boolean)
  private final case class FollowerObservation(localAlignedCount: Int, facilityPulseAligned: Boolean)

  private final case class BarrierOutcome(artifact: Signed[Int], operationalValue: Int)
  private implicit val barrierOutcomeEq: Eq[BarrierOutcome] =
    Eq.instance { (left, right) =>
      Signed.eq[Int].eqv(left.artifact, right.artifact) && left.operationalValue === right.operationalValue
    }

  private def proof(id: String): SignatureProof =
    SignatureProof(Id(Hex(id)), Signature(Hex("00")))

  test("recovery-seed barrier retries inspect, record, sleep, and queue failures until start is accepted") {
    val inspectFailure = new RuntimeException("inspect-failed")
    val recordFailure = new RuntimeException("record-failed")
    val sleepFailure = new RuntimeException("sleep-failed")
    val queueFailure = new RuntimeException("queue-failed")

    for {
      inspections <- Ref.of[IO, Int](0)
      recordAttempts <- Ref.of[IO, List[Long]](List.empty)
      pauses <- Ref.of[IO, Int](0)
      queueOffers <- Ref.of[IO, Int](0)
      startPending <- Ref.of[IO, Boolean](true)
      reports <- Ref.of[IO, List[String]](List.empty)

      inspect = inspections.modify {
        case 0 => 1 -> inspectFailure.raiseError[IO, Observation]
        case 1 => 2 -> Observation(aligned = false).pure[IO]
        case n => (n + 1) -> Observation(aligned = true).pure[IO]
      }.flatten
      record = (_: Observation, attempt: Long) =>
        recordAttempts.modify { attempts =>
          val next = attempts :+ attempt
          next -> (if (attempts.isEmpty) recordFailure.raiseError[IO, Unit] else IO.unit)
        }.flatten
      pause = pauses.modify {
        case 0 => 1 -> sleepFailure.raiseError[IO, Unit]
        case n => (n + 1) -> IO.unit
      }.flatten
      offer = queueOffers.modify {
        case 0 => 1 -> queueFailure.raiseError[IO, Unit]
        case n => (n + 1) -> startPending.set(false)
      }.flatten
      report = (stage: String, _: Throwable) => reports.update(_ :+ stage)

      _ <- StateTransitions.runRecoverySeedBarrierLoop(
        inspect,
        (observation: Observation) => observation.aligned,
        record,
        pause,
        offer,
        startPending.get,
        report
      )

      observedInspections <- inspections.get
      observedRecordAttempts <- recordAttempts.get
      observedPauses <- pauses.get
      observedQueueOffers <- queueOffers.get
      observedReports <- reports.get
    } yield
      expect.same(4, observedInspections) &&
        expect.same(List(2L, 3L, 4L), observedRecordAttempts) &&
        expect.same(3, observedPauses) &&
        expect.same(2, observedQueueOffers) &&
        expect.same(List("inspect", "sleep", "record", "queue_offer"), observedReports)
  }

  test("recovery-seed barrier survives a failed error reporter") {
    val inspectFailure = new RuntimeException("inspect-failed")
    val reporterFailure = new RuntimeException("logger-and-metrics-failed")

    for {
      inspections <- Ref.of[IO, Int](0)
      queueOffers <- Ref.of[IO, Int](0)
      startPending <- Ref.of[IO, Boolean](true)
      inspect = inspections.modify {
        case 0 => 1 -> inspectFailure.raiseError[IO, Observation]
        case n => (n + 1) -> Observation(aligned = true).pure[IO]
      }.flatten
      _ <- StateTransitions.runRecoverySeedBarrierLoop(
        inspect,
        (observation: Observation) => observation.aligned,
        (_: Observation, _: Long) => IO.unit,
        IO.unit,
        queueOffers.update(_ + 1) >> startPending.set(false),
        startPending.get,
        (_, _) => reporterFailure.raiseError[IO, Unit]
      )
      observedInspections <- inspections.get
      observedQueueOffers <- queueOffers.get
    } yield expect.same(2, observedInspections) && expect.same(1, observedQueueOffers)
  }

  test("recovery-seed barrier keeps retrying until the gate acknowledges establishment") {
    for {
      inspections <- Ref.of[IO, Int](0)
      offers <- Ref.of[IO, Int](0)
      pending <- Ref.of[IO, Boolean](true)
      _ <- StateTransitions.runRecoverySeedBarrierLoop(
        inspections.update(_ + 1).as(Observation(aligned = true)),
        (observation: Observation) => observation.aligned,
        (_: Observation, _: Long) => IO.unit,
        IO.unit,
        offers.updateAndGet(_ + 1).flatMap(count => pending.set(count < 2)),
        pending.get,
        (_, _) => IO.unit
      )
      observedInspections <- inspections.get
      observedOffers <- offers.get
    } yield expect.same(2, observedInspections) && expect.same(2, observedOffers)
  }

  test("shared first-round barrier has no elapsed-attempt escape below quorum") {
    val belowQuorumAttempts = 20

    for {
      inspections <- Ref.of[IO, Int](0)
      offers <- Ref.of[IO, Int](0)
      pending <- Ref.of[IO, Boolean](true)
      inspect = inspections.modify { current =>
        val next = current + 1
        next -> Observation(aligned = next > belowQuorumAttempts)
      }
      _ <- StateTransitions.runFirstRoundAlignmentLoop(
        inspect,
        (observation: Observation) => observation.aligned,
        (_: Observation, _: Long) => IO.unit,
        IO.unit,
        offers.update(_ + 1) >> pending.set(false),
        pending.get,
        (_, _) => IO.unit
      )
      observedInspections <- inspections.get
      observedOffers <- offers.get
    } yield
      expect.same(belowQuorumAttempts + 1, observedInspections) &&
        expect.same(1, observedOffers)
  }

  test("asymmetric follower views cannot release without an authenticated aligned Facility pulse") {
    val observations = List(
      FollowerObservation(localAlignedCount = 5, facilityPulseAligned = false),
      FollowerObservation(localAlignedCount = 2, facilityPulseAligned = false),
      FollowerObservation(localAlignedCount = 1, facilityPulseAligned = true)
    )

    for {
      remaining <- Ref.of[IO, List[FollowerObservation]](observations)
      seen <- Ref.of[IO, List[FollowerObservation]](List.empty)
      offers <- Ref.of[IO, Int](0)
      pending <- Ref.of[IO, Boolean](true)
      inspect <- IO.pure(
        remaining.modify {
          case head :: tail => tail -> head
          case Nil          => Nil -> observations.last
        }
      )
      _ <- StateTransitions.runFirstRoundAlignmentLoop(
        inspect,
        (observation: FollowerObservation) => observation.facilityPulseAligned,
        (observation: FollowerObservation, _: Long) => seen.update(_ :+ observation),
        IO.unit,
        offers.update(_ + 1) >> pending.set(false),
        pending.get,
        (_, _) => IO.unit
      )
      observed <- seen.get
      observedOffers <- offers.get
    } yield
      expect.same(observations, observed) &&
        expect.same(1, observedOffers)
  }

  pureTest("recovery-seed alignment uses outcome value equality and ignores only Signed proof-subset differences") {
    val expected = BarrierOutcome(Signed(7, NonEmptySet.one(proof("01"))), operationalValue = 11)
    val differentProofs = BarrierOutcome(Signed(7, NonEmptySet.one(proof("02"))), operationalValue = 11)
    val differentOperationalValue = differentProofs.copy(operationalValue = 12)

    expect.same(
      StateTransitions.RecoverySeedPeerOutcome.Aligned,
      StateTransitions.recoverySeedPeerOutcome(expected, differentProofs.some)
    ) &&
    expect.same(
      StateTransitions.RecoverySeedPeerOutcome.Mismatched,
      StateTransitions.recoverySeedPeerOutcome(expected, differentOperationalValue.some)
    )
  }

  pureTest("a six-member recovery seed requires every named external member and ignores unrelated Ready peers") {
    val planned = SortedSet.from((1 to 6).map(n => PeerId(Hex(f"$n%02x" * 64))))
    val self = planned.head
    val external = planned - self
    val unrelated = PeerId(Hex("ff" * 64))
    val responsive = external.toList.map(_ -> NodeState.Ready).toMap + (unrelated -> NodeState.Ready)
    val aligned = external.toList.map(_ -> StateTransitions.RecoverySeedPeerOutcome.Aligned).toMap
    val complete = StateTransitions.recoverySeedBarrierStatus(self, planned, selfReady = true, responsive, aligned)
    val missingOne = StateTransitions.recoverySeedBarrierStatus(
      self,
      planned,
      selfReady = true,
      responsive - external.last,
      aligned - external.last
    )

    expect(complete.aligned) &&
    expect(!missingOne.aligned) &&
    expect.same(SortedSet(external.last), missingOne.missingSession) &&
    expect(!complete.expectedExternal.contains(unrelated))
  }
}
