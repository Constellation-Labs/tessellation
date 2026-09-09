package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.{AdmissionProofHistory, OpenAdmissionPolicy}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object AdmissionProofHistoryWiringSuite extends SimpleIOSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))
  private def hash(ordinal: Long): Hash = Hash.fromBytes(s"parent-$ordinal".getBytes("UTF-8"))

  private val committee = (1 to 3).map(peer).toSet

  private def observe(
    ref: Ref[IO, AdmissionProofHistory.History],
    ordinal: Long,
    signers: Option[Set[PeerId]] = Some(committee)
  ): IO[Option[AdmissionProofHistory.History]] =
    StallDetector.observeAdmissionProofHistory(ref, signers, ordinal.some, hash(ordinal))

  private def decision(history: Option[AdmissionProofHistory.History], cadenceAllowed: Boolean) =
    OpenAdmissionPolicy.evaluate(
      cadenceAllowed = cadenceAllowed,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true,
      locallyObservedParentProofHistory = history
    )

  test("StallDetector wiring accumulates one observation per finalized parent and gates both admission lanes") {
    for {
      ref <- Ref.of[IO, AdmissionProofHistory.History](AdmissionProofHistory.History.empty)
      first <- observe(ref, 100L)
      duplicate <- observe(ref, 100L)
      second <- observe(ref, 101L)
      third <- observe(ref, 102L)
      firstDecision = decision(first, cadenceAllowed = true)
      duplicateDecision = decision(duplicate, cadenceAllowed = true)
      secondDecision = decision(second, cadenceAllowed = true)
      thirdOnCadence = decision(third, cadenceAllowed = true)
      thirdOffCadence = decision(third, cadenceAllowed = false)
    } yield
      expect.all(
        first.exists(_.depth == 1),
        duplicate.exists(_.depth == 1),
        second.exists(_.depth == 2),
        !firstDecision.allowsProbationAdmission,
        !firstDecision.allowsOpenAdmission,
        !duplicateDecision.allowsProbationAdmission,
        !secondDecision.allowsProbationAdmission,
        third.exists(_.parents.map(_.ordinal) == Vector(100L, 101L, 102L)),
        thirdOnCadence.allowsProbationAdmission,
        thirdOnCadence.allowsOpenAdmission,
        thirdOffCadence.allowsProbationAdmission,
        !thirdOffCadence.allowsOpenAdmission
      )
  }

  test("download and rollback pre-initialize wiring clear history before the layer callback") {
    for {
      ref <- Ref.of[IO, AdmissionProofHistory.History](AdmissionProofHistory.History.empty)
      callbackObservations <- Ref.of[IO, Vector[(String, Int)]](Vector.empty)
      _ <- observe(ref, 1L) >> observe(ref, 2L) >> observe(ref, 3L)
      beforeDownload <- ref.get
      callback = (boundary: String) => ref.get.flatMap(h => callbackObservations.update(_ :+ (boundary -> h.depth)))
      _ <- ConsensusEventLoop.resetAdmissionProofHistoryBefore(ref, callback.some)("download")
      afterDownload <- ref.get
      firstNewLineage <- observe(ref, 50L)
      _ <- ConsensusEventLoop.resetAdmissionProofHistoryBefore(ref, callback.some)("rollback")
      afterRollback <- ref.get
      observedByCallbacks <- callbackObservations.get
      afterResetDecision = decision(firstNewLineage, cadenceAllowed = true)
    } yield
      expect.all(
        beforeDownload.depth == AdmissionProofHistory.RequiredConsecutiveParents,
        afterDownload == AdmissionProofHistory.History.empty,
        firstNewLineage.exists(_.parents.map(_.ordinal) == Vector(50L)),
        !afterResetDecision.allowsProbationAdmission,
        !afterResetDecision.allowsOpenAdmission,
        afterRollback == AdmissionProofHistory.History.empty,
        observedByCallbacks == Vector("download" -> 0, "rollback" -> 0)
      )
  }

  test("Currency-style absent proof wiring is inert and retains cadence-only behavior") {
    for {
      ref <- Ref.of[IO, AdmissionProofHistory.History](AdmissionProofHistory.History.empty)
      _ <- observe(ref, 1L) >> observe(ref, 2L) >> observe(ref, 3L)
      before <- ref.get
      observed <- StallDetector.observeAdmissionProofHistory(ref, none, 4L.some, hash(4L))
      after <- ref.get
      onCadence = OpenAdmissionPolicy.evaluate(
        cadenceAllowed = true,
        currentCommittee = committee,
        locallyObservedParentSigners = None,
        quorumThresholdFraction = 1.0,
        headroomGateActive = true,
        locallyObservedParentProofHistory = None
      )
      offCadence = onCadence.copy(cadenceAllowed = false)
    } yield
      expect.all(
        observed.isEmpty,
        after == before,
        onCadence.headroom.isEmpty,
        onCadence.sustainedHeadroom.isEmpty,
        onCadence.allowsProbationAdmission,
        onCadence.allowsOpenAdmission,
        offCadence.allowsProbationAdmission,
        !offCadence.allowsOpenAdmission
      )
  }
}
