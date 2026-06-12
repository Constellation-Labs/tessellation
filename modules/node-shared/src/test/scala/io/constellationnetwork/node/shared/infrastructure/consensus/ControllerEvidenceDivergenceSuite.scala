package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import io.circe.Printer
import io.circe.syntax._
import weaver.SimpleIOSuite

/** Divergence regression for the controller-evidence architecture (the alpha.92/129/147 wedge class).
  *
  * Two outcomes that agree on the SIGNED chain evidence (`controllerEvidence`, `penaltyUntil`) but carry DIFFERENT per-peer operational
  * state (simulating a poisoned sidecar / snapshot.peerHistory seed on restart) MUST derive identical controller state, and feeding that
  * derived state into `ActiveFacilitatorAdmission.fromRecentSigners` MUST yield identical active lists. The carried maps are the divergence
  * channel being retired; the suite also demonstrates that channel is real (carried scores DO diverge the admission result).
  *
  * The fixture stands in for `GlobalConsensusOutcome` / `CurrencyConsensusOutcome`: node-shared cannot depend on the dag-l0 / currency-l0
  * schema (dependency direction), so it carries exactly the outcome fields under test.
  */
object ControllerEvidenceDivergenceSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')

  private final case class OutcomeFixture(
    controllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    penaltyUntil: SortedMap[PeerId, SnapshotOrdinal],
    // Carried (copied-forward, restart-seeded) controller state -- the divergence channel.
    activeAdmissionScores: SortedMap[PeerId, Int],
    peerQuality: SortedMap[PeerId, (Int, Int)],
    peerTiers: SortedMap[PeerId, Int],
    peerViewChanges: SortedMap[PeerId, Long],
    peerSelfHealth: SortedMap[PeerId, SelfHealthHint]
  )

  private def entry(roundStart: Set[PeerId], signers: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(signers),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  // Five finalized rounds: a, b, d sign every round; c is in roundStart every round but
  // signed only the first (a chronic misser whose sign has rotated out of the recent sets).
  private val sharedEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    SortedMap.from(
      (10L to 14L).map { o =>
        val signers = if (o == 10L) Set(a, b, c, d) else Set(a, b, d)
        ord(o) -> entry(Set(a, b, c, d), signers)
      }
    )

  private val sharedPenaltyUntil: SortedMap[PeerId, SnapshotOrdinal] =
    SortedMap(c -> ord(120L))

  // Healthy node: carried state matches reality.
  private val outcomeA = OutcomeFixture(
    controllerEvidence = sharedEvidence,
    penaltyUntil = sharedPenaltyUntil,
    activeAdmissionScores = SortedMap(a -> 150, b -> 150, c -> 60, d -> 150),
    peerQuality = SortedMap(a -> (5, 5), b -> (5, 5), c -> (1, 5), d -> (5, 5)),
    peerTiers = SortedMap(a -> 2, b -> 2, c -> 1, d -> 2),
    peerViewChanges = SortedMap(c -> 1L),
    peerSelfHealth = SortedMap(a -> SelfHealthHint.Healthy, b -> SelfHealthHint.Healthy, d -> SelfHealthHint.Healthy)
  )

  // Restarted node with a poisoned local seed: identical SIGNED evidence, divergent
  // carried maps (b demoted below the demote threshold, c promoted to a perfect record,
  // a and b smeared with view-change blame and Critical health to skew leader selection).
  private val outcomeB = OutcomeFixture(
    controllerEvidence = sharedEvidence,
    penaltyUntil = sharedPenaltyUntil,
    activeAdmissionScores = SortedMap(a -> 150, b -> 10, c -> 150, d -> 90),
    peerQuality = SortedMap(a -> (5, 5), b -> (1, 9), c -> (9, 9), d -> (2, 5)),
    peerTiers = SortedMap(a -> 2, b -> 0, c -> 2, d -> 1),
    peerViewChanges = SortedMap(a -> 9L, b -> 9L),
    peerSelfHealth = SortedMap(a -> SelfHealthHint.Critical, b -> SelfHealthHint.Critical, d -> SelfHealthHint.Degraded)
  )

  private val selected = List(a, b, c, d)

  private def recentSignersOf(evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]): SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    evidence.map { case (o, en) => o -> en.completedSigners }

  private def admissionFromDerived(fixture: OutcomeFixture): ActiveFacilitatorAdmission.Result = {
    val derived = ControllerEvidenceDerivation.derive(fixture.controllerEvidence)

    ActiveFacilitatorAdmission.fromRecentSigners(
      selected = selected,
      recentSigners = recentSignersOf(fixture.controllerEvidence),
      peerQuality = derived.map { case (pid, s) => pid -> s.derivedQuality },
      activeScores = derived.map { case (pid, s) => pid -> s.derivedScore },
      minActiveSize = 2,
      targetActiveSize = 3,
      maxActiveSize = 4,
      minParticipationObservations = 3,
      minParticipationRatio = 0.5
    )
  }

  private def admissionFromCarried(fixture: OutcomeFixture): ActiveFacilitatorAdmission.Result =
    ActiveFacilitatorAdmission.fromRecentSigners(
      selected = selected,
      recentSigners = recentSignersOf(fixture.controllerEvidence),
      peerQuality = fixture.peerQuality,
      activeScores = fixture.activeAdmissionScores,
      minActiveSize = 2,
      targetActiveSize = 3,
      maxActiveSize = 4,
      minParticipationObservations = 3,
      minParticipationRatio = 0.5
    )

  pureTest("identical evidence derives identical scores, tiers, and quality despite divergent carried state") {
    val derivedA = ControllerEvidenceDerivation.derive(outcomeA.controllerEvidence)
    val derivedB = ControllerEvidenceDerivation.derive(outcomeB.controllerEvidence)

    expect(outcomeA.activeAdmissionScores != outcomeB.activeAdmissionScores) &&
    expect(outcomeA.peerTiers != outcomeB.peerTiers) &&
    expect.same(derivedA, derivedB) &&
    expect.same(
      derivedA.map { case (pid, s) => pid -> s.derivedScore },
      derivedB.map { case (pid, s) => pid -> s.derivedScore }
    ) &&
    expect.same(
      derivedA.map { case (pid, s) => pid -> s.derivedTier },
      derivedB.map { case (pid, s) => pid -> s.derivedTier }
    )
  }

  pureTest("identical penaltyUntil yields identical pure penalty checks despite divergent carried state") {
    def penalizedAt(fixture: OutcomeFixture, at: SnapshotOrdinal): Set[PeerId] =
      fixture.penaltyUntil.collect { case (pid, until) if until.value.value > at.value.value => pid }.toSet

    expect.same(penalizedAt(outcomeA, ord(100L)), penalizedAt(outcomeB, ord(100L))) &&
    expect.same(Set(c), penalizedAt(outcomeA, ord(100L))) &&
    expect.same(Set.empty[PeerId], penalizedAt(outcomeA, ord(120L)))
  }

  pureTest("derived scores feed ActiveFacilitatorAdmission to identical active lists") {
    val resultA = admissionFromDerived(outcomeA)
    val resultB = admissionFromDerived(outcomeB)

    expect.same(resultA.active, resultB.active) &&
    expect.same(resultA.exclusions, resultB.exclusions) &&
    expect.same(List(a, b, d), resultA.active)
  }

  pureTest("carried scores are a real divergence channel: poisoned seed changes the active list") {
    val resultA = admissionFromCarried(outcomeA)
    val resultB = admissionFromCarried(outcomeB)

    // The healthy node retains b (score 150); the poisoned node demotes b (score 10 < 40)
    // from the recent-signer pool. This is exactly the wedge the derivation closes.
    expect(resultA.active != resultB.active) &&
    expect(resultA.active.contains(b)) &&
    expect(!resultB.active.contains(b))
  }

  // ===========================================================================
  // Stage 4 wiring: the full StateCreator derivation chain through ControllerInputs
  // ===========================================================================

  private val facilitatorSelector: FacilitatorSelector = FacilitatorSelector.make(Some(10))

  /** Everything the StateCreator derives from the controller inputs for a round. `active` is the list the creator freezes as the
    * round-start committee (`Facilitators(active)`), i.e. the set that feeds `facilitatorsHash`.
    */
  private final case class RoundDerivation(
    active: List[PeerId],
    core: List[PeerId],
    tier1: List[PeerId],
    leaderPool: List[PeerId],
    leader: PeerId
  )

  /** Mirror of the StateCreators' stage-4 pipeline: `controllerInputsWithFallback` -> `chooseActive` -> `CommitteeBuilder.build` ->
    * `LeaderEligibility.fromRecentSigners` -> `selectLeaderWeighted`, with the same input-to-call-site mapping as
    * GlobalSnapshotConsensusStateCreator / CurrencySnapshotConsensusStateCreator. Entropy and viewNumber are fixed
    * (`Hash.empty` / `0`) so the leader comparison is deterministic -- `selectLeaderWeighted` is a pure function of its arguments.
    */
  private def deriveRound(fixture: OutcomeFixture): RoundDerivation = {
    val inputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = fixture.controllerEvidence,
      carriedScores = fixture.activeAdmissionScores.toMap,
      carriedQuality = fixture.peerQuality.toMap,
      carriedTiers = fixture.peerTiers,
      carriedViewChanges = fixture.peerViewChanges.toMap,
      carriedSelfHealth = fixture.peerSelfHealth.toMap
    )
    val admission = ConsensusPeerController.chooseActive(
      ConsensusPeerController.AdmissionInput(
        selected = selected,
        recentSigners = recentSignersOf(fixture.controllerEvidence),
        peerQuality = inputs.peerQuality,
        activeScores = inputs.activeScores,
        minActiveSize = 2,
        targetActiveSize = 3,
        maxActiveSize = 4,
        minParticipationObservations = 3,
        minParticipationRatio = 0.5,
        config = ConsensusPeerController.Config.default
      )
    )
    val committees = CommitteeBuilder.build(
      candidates = admission.active,
      priorTiers = inputs.peerTiers,
      peerQuality = inputs.peerQuality,
      coreFloor = 2,
      minObservations = 3,
      minRatio = 0.5,
      nonCorePeers = admission.probationAdmitted.toSet
    )
    val eligibility = LeaderEligibility.fromRecentSigners(
      core = committees.core,
      peerQuality = inputs.peerQuality,
      recentSigners = recentSignersOf(fixture.controllerEvidence),
      minParticipationObservations = 3,
      minLeaderPoolSize = 2
    )
    val leader = facilitatorSelector.selectLeaderWeighted(
      eligibility.leaderPool,
      Hash.empty,
      viewNumber = 0,
      qualityScores = inputs.peerQuality,
      selfHealthHints = inputs.selfHealth,
      peerViewChanges = inputs.viewChanges
    )

    RoundDerivation(admission.active, committees.core, committees.tier1, eligibility.leaderPool, leader)
  }

  pureTest("stage-4 wiring: poisoned carried maps cannot diverge admission, committees, leader pool, leader, or facilitators") {
    val roundA = deriveRound(outcomeA)
    val roundB = deriveRound(outcomeB)

    // The carried channels really are divergent in the fixtures...
    expect(outcomeA.activeAdmissionScores != outcomeB.activeAdmissionScores) &&
    expect(outcomeA.peerQuality != outcomeB.peerQuality) &&
    expect(outcomeA.peerTiers != outcomeB.peerTiers) &&
    expect(outcomeA.peerViewChanges != outcomeB.peerViewChanges) &&
    expect(outcomeA.peerSelfHealth != outcomeB.peerSelfHealth) &&
    // ...yet every committee/leader derivation downstream of ControllerInputs is identical,
    // including the round-start facilitators list that feeds facilitatorsHash.
    expect.same(roundA, roundB) &&
    expect.same(roundA.active, roundB.active) &&
    expect.same(roundA.core, roundB.core) &&
    expect.same(roundA.tier1, roundB.tier1) &&
    expect.same(roundA.leaderPool, roundB.leaderPool) &&
    expect.same(roundA.leader, roundB.leader)
  }

  pureTest("signed-artifact peerHistory bytes are identical for identical evidence despite divergent carried perPeer state") {
    // Stage 4: `signedArtifactOperationalState` is the ONLY peerHistory payload allowed into
    // signed artifact bytes. It is built exclusively from the deterministic windows, so two
    // nodes whose carried perPeer maps diverged (the fixture pair above) MUST still produce
    // byte-identical signed payloads. Production encodes with `dropNullValues = true`; same
    // printer here so this compares the actual signed-byte shape.
    val printer = Printer.noSpaces.copy(dropNullValues = true)

    def signedPayload(fixture: OutcomeFixture) =
      ControllerEvidenceDerivation.signedArtifactOperationalState(
        recentProofSizes = fixture.controllerEvidence.map { case (o, en) => o -> en.completedSigners.size },
        recentSigners = recentSignersOf(fixture.controllerEvidence),
        controllerEvidence = Some(fixture.controllerEvidence),
        penaltyUntil = Some(fixture.penaltyUntil)
      )

    val payloadA = signedPayload(outcomeA)
    val payloadB = signedPayload(outcomeB)

    expect(outcomeA.activeAdmissionScores != outcomeB.activeAdmissionScores) &&
    expect.same(payloadA, payloadB) &&
    expect.same(payloadA.asJson.printWith(printer), payloadB.asJson.printWith(printer)) &&
    expect(payloadA.perPeer.isEmpty) &&
    expect(payloadA.recentRoundEndTimes.isEmpty) &&
    expect.same(Some(outcomeA.controllerEvidence), payloadA.controllerEvidence) &&
    expect.same(Some(outcomeA.penaltyUntil), payloadA.penaltyUntil)
  }
}
