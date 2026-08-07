package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Replays the E2E dev cold-start round-5 state that produced core=0 and the selectLeaderWeighted empty-facilitators crash (CI run
  * 29593274327). Pure replay of the StateCreator pipeline: evidence (3 signed rounds) -> controllerInputsWithFallback -> chooseActive ->
  * CommitteeBuilder. Prints every intermediate map so the poisoned input is visible, then asserts the liveness invariant that the crash
  * violated.
  */
object ColdStartRound5ReproSuite extends SimpleIOSuite {

  private def peer(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private def entry(roundStart: Set[PeerId], completed: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(completed),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  private val devControllerConfig =
    ConsensusPeerController.Config(
      promoteThreshold = 100,
      retainThreshold = 70,
      demoteThreshold = 40,
      maxScore = 150,
      signatureReward = 20,
      responderReward = 5,
      missedActivePenalty = 15,
      timeoutMissingPenalty = 10,
      evictedPenalty = 40,
      degradedPenalty = 5,
      criticalPenalty = 20,
      passiveDecay = 1,
      maxExpansionPerRound = 1,
      minProbationReentrySlots = 3,
      recentSignerWindow = 10
    )

  private def replay(n: Int, floor: Int, coreFloor: Int): (ActiveFacilitatorAdmission.Result, CommitteeBuilder.Committees, String) = {
    val nodes = (0 until n).map(peer).toList
    val evidence = SortedMap.from((2L to 4L).map(o => ord(o) -> entry(nodes.toSet, nodes.toSet)))
    val recentSigners = evidence.map { case (k, e) => k -> e.completedSigners }
    val inputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = evidence,
      carriedScores = Map.empty,
      carriedQuality = Map.empty,
      carriedTiers = SortedMap.empty,
      carriedViewChanges = Map.empty,
      carriedSelfHealth = Map.empty
    )
    val result = ConsensusPeerController.chooseActive(
      ConsensusPeerController.AdmissionInput(
        selected = nodes,
        recentSigners = recentSigners,
        latestRoundStartFacilitators = evidence.last._2.roundStartFacilitators.toSet,
        peerQuality = inputs.peerQuality,
        activeScores = inputs.activeScores,
        sizing = ConsensusPeerController.AdmissionSizing(
          emergencyBypassFloor = floor,
          targetActiveSize = 7,
          maxActiveSize = 13
        ),
        minParticipationObservations = 10,
        minParticipationRatio = 0.5,
        config = devControllerConfig
      )
    )
    val committees = CommitteeBuilder.build(
      candidates = result.active,
      priorTiers = inputs.peerTiers,
      peerQuality = inputs.peerQuality,
      coreFloor = coreFloor,
      minObservations = 10,
      minRatio = 0.5,
      nonCorePeers = result.probationAdmitted.toSet,
      chronicMisses = inputs.chronicMisses,
      activeScores = inputs.activeScores
    )
    val dump =
      s"n=$n floor=$floor coreFloor=$coreFloor | " +
        s"scores=${inputs.activeScores.map { case (p, s) => p.value.value.take(2) + "->" + s }.toList.sorted.mkString(",")} | " +
        s"tiers=${inputs.peerTiers.map { case (p, t) => p.value.value.take(2) + "->" + t }.toList.sorted.mkString(",")} | " +
        s"quality=${inputs.peerQuality.map { case (p, q) => p.value.value.take(2) + "->" + q }.toList.sorted.mkString(",")} | " +
        s"chronic=${inputs.chronicMisses.map { case (p, m) => p.value.value.take(2) + "->" + m }.toList.sorted.mkString(",")} | " +
        s"filterApplied=${result.recentFilterApplied} pool=${result.recentSignerPoolSize} " +
        s"sticky=${result.stickyProbationCandidateSize} fresh=${result.freshProbationCandidateSize} " +
        s"probation=${result.probationAdmitted.map(_.value.value.take(2)).mkString(",")} " +
        s"active=${result.active.map(_.value.value.take(2)).mkString(",")} | " +
        s"core=${committees.core.size} tier1=${committees.tier1.size} witness=${committees.witness.size}"
    (result, committees, dump)
  }

  pureTest("REPRO gl0 dev cold start round 5: three signed rounds must never yield an empty Core") {
    val (_, committees, dump) = replay(n = 3, floor = 3, coreFloor = 3)
    println(s"[REPRO gl0] $dump")
    expect(committees.core.nonEmpty, s"CRASH STATE REPRODUCED (core empty): $dump")
  }

  pureTest("REPRO ml0 dev cold start round 5: single signer must never yield an empty Core") {
    val (_, committees, dump) = replay(n = 1, floor = 4, coreFloor = 3)
    println(s"[REPRO ml0] $dump")
    expect(committees.core.nonEmpty, s"CRASH STATE REPRODUCED (core empty): $dump")
  }

  pureTest("the emergency bypass carries empty lanes: no probation while gating is off") {
    val (result, _, dump) = replay(n = 3, floor = 3, coreFloor = 3)
    expect(!result.recentFilterApplied, s"cold start round 5 must be a bypass round: $dump") &&
    expect(result.probationAdmitted.isEmpty, s"bypass must not admit probation: $dump") &&
    expect.same(0, result.stickyProbationCandidateSize) &&
    expect.same(0, result.freshProbationCandidateSize) &&
    expect(!result.freshProbationStarved)
  }
}
