package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import weaver.SimpleIOSuite

/** Guards the IntegrationNet rc.2 active-floor oscillator.
  *
  * Using Core size as the emergency bypass threshold made an 8-10 peer pool alternate between score gating and full-pool fallback around
  * the normal operating point. The controller must keep that threshold at the configured collapse floor, while sticky non-Core probation
  * gives responsive climbers enough consecutive signed rounds to graduate without reintroducing active-set churn.
  */
object ActiveFloorGatingSuite extends SimpleIOSuite {

  private def peer(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val Floor = 3
  private val Core = 9
  private val Target = 19
  private val Max = 37
  private val ProbationSlots = 9

  private val eventCutter =
    EventCutterConfig(
      maxBinarySizeBytes = PosInt(1024),
      maxUpdateNodeParametersSize = PosInt(1024)
    )

  private def consensusConfig: ConsensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = eventCutter,
      activeFacilitatorFloor = Floor,
      activeFacilitatorTarget = Some(Target),
      activeFacilitatorMax = Some(Max)
    )

  private val controllerConfig =
    ConsensusPeerController.Config.default.copy(
      maxExpansionPerRound = 1,
      minProbationReentrySlots = ProbationSlots,
      recentSignerWindow = 10
    )

  private def entry(roundStart: Set[PeerId], completed: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(completed),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  private def recentSigners(
    evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]
  ): SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    evidence.map { case (ordinal, round) => ordinal -> round.completedSigners }

  private def choose(
    selected: List[PeerId],
    evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    sizing: ConsensusPeerController.AdmissionSizing
  ): (ActiveFacilitatorAdmission.Result, ControllerEvidenceDerivation.ControllerInputs) = {
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
        selected = selected,
        recentSigners = recentSigners(evidence),
        latestRoundStartFacilitators = evidence.lastOption.map(_._2.roundStartFacilitators.toSet).getOrElse(Set.empty),
        peerQuality = inputs.peerQuality,
        activeScores = inputs.activeScores,
        sizing = sizing,
        minParticipationObservations = 10,
        minParticipationRatio = 0.5,
        config = controllerConfig
      )
    )
    result -> inputs
  }

  pureTest("controller resolves the emergency bypass floor independently of Core size") {
    val intnet = ConsensusPeerController.AdmissionSizing.from(consensusConfig, coreCommitteeSize = Core, selectedSize = 20)
    val largerCore = ConsensusPeerController.AdmissionSizing.from(consensusConfig, coreCommitteeSize = 15, selectedSize = 20)

    expect.same(Floor, intnet.emergencyBypassFloor) &&
    expect.same(Target, intnet.targetActiveSize) &&
    expect.same(Max, intnet.maxActiveSize) &&
    expect.same(intnet.emergencyBypassFloor, largerCore.emergencyBypassFloor)
  }

  pureTest("currency defaults preserve the four-peer emergency floor on a three-node metagraph") {
    val metagraph = ConsensusPeerController.AdmissionSizing.from(
      consensusConfig.copy(
        activeFacilitatorFloor = 4,
        activeFacilitatorTarget = None,
        activeFacilitatorMax = None
      ),
      coreCommitteeSize = 3,
      selectedSize = 3
    )

    expect.same(4, metagraph.emergencyBypassFloor) &&
    expect.same(3, metagraph.targetActiveSize) &&
    expect.same(3, metagraph.maxActiveSize)
  }

  pureTest("probation climbers stay bounded and graduate without active-set oscillation") {
    val retained = (0 until 8).map(peer).toList
    val climbers = (8 until 20).map(peer).toList
    val selected = retained ++ climbers
    val initialEvidence = SortedMap.from(
      (1L to 5L).map(n => ord(n) -> entry(retained.toSet, retained.toSet))
    )
    val sizing = ConsensusPeerController.AdmissionSizing.from(consensusConfig, Core, selected.size)

    final case class Step(
      evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
      results: List[ActiveFacilitatorAdmission.Result],
      probationCoreOverlaps: List[Set[PeerId]]
    )

    val simulation = (1 to 6).foldLeft(Step(initialEvidence, List.empty, List.empty)) { (step, _) =>
      val (result, inputs) = choose(selected, step.evidence, sizing)
      val committees = CommitteeBuilder.build(
        candidates = result.active,
        priorTiers = inputs.peerTiers,
        peerQuality = inputs.peerQuality,
        coreFloor = Core,
        minObservations = 10,
        minRatio = 0.5,
        nonCorePeers = result.probationAdmitted.toSet,
        chronicMisses = inputs.chronicMisses,
        activeScores = inputs.activeScores
      )
      val nextOrdinal = ord(step.evidence.lastKey.value.value + 1L)
      val nextEvidence = SortedMap.from(
        (step.evidence.updated(nextOrdinal, entry(result.active.toSet, result.active.toSet))).toList.takeRight(10)
      )

      Step(
        evidence = nextEvidence,
        results = step.results :+ result,
        probationCoreOverlaps = step.probationCoreOverlaps :+ committees.core.toSet.intersect(result.probationAdmitted.toSet)
      )
    }

    val activeSizes = 8 :: simulation.results.map(_.active.size)
    val firstProbationCohort = simulation.results.head.probationAdmitted.toSet
    val cohortStayedSeated =
      simulation.results.take(4).forall(result => firstProbationCohort.subsetOf(result.active.toSet))
    val cohortGraduated =
      simulation.results.drop(4).exists { result =>
        firstProbationCohort.subsetOf(result.active.toSet) &&
        firstProbationCohort.intersect(result.probationAdmitted.toSet).isEmpty
      }

    expect(simulation.results.forall(_.recentFilterApplied), "the filter must not toggle around the Core operating size") &&
    expect(activeSizes.zip(activeSizes.drop(1)).forall { case (a, b) => b >= a }, s"active sizes regressed: $activeSizes") &&
    expect(cohortStayedSeated, "latest-round probation signers must retain a bounded seat while climbing") &&
    expect(cohortGraduated, "the first probation cohort must graduate out of probation") &&
    expect(simulation.results.forall(_.probationAdmittedSize <= ProbationSlots), "probation must remain bounded") &&
    expect(simulation.probationCoreOverlaps.forall(_.isEmpty), "probation peers must remain outside Core") &&
    expect(simulation.results.last.active.size >= Target, s"active set did not close the target deficit: $activeSizes")
  }

  pureTest("a probation climber that misses the latest round loses its sticky seat") {
    val retained = (0 until 8).map(peer).toList
    val climber = peer(8)
    val selected = retained :+ climber
    val sizing = ConsensusPeerController.AdmissionSizing.from(consensusConfig, Core, selected.size)
    val initialEvidence = SortedMap.from(
      (1L to 5L).map(n => ord(n) -> entry(retained.toSet, retained.toSet))
    )
    val (first, _) = choose(selected, initialEvidence, sizing)
    val evidenceAfterMiss = initialEvidence.updated(
      ord(6L),
      entry(first.active.toSet, first.active.toSet - climber)
    )
    val (afterMiss, _) = choose(selected, evidenceAfterMiss, sizing)
    val missedLatestRound = afterMiss.exclusions.collect {
      case exclusion if exclusion.reason == ActiveFacilitatorAdmission.ExclusionReason.MissedLatestRound =>
        exclusion.peerId
    }.toSet

    expect(first.probationAdmitted.contains(climber)) &&
    expect(!afterMiss.probationAdmitted.contains(climber)) &&
    expect(!afterMiss.active.contains(climber)) &&
    expect.same(Set(climber), missedLatestRound)
  }

  pureTest("sticky probation saturation exposes fresh-candidate starvation") {
    val retained = (0 until 8).map(peer).toList
    val sticky = (8 until 17).map(peer).toList
    val fresh = (17 until 29).map(peer).toList
    val selected = retained ++ sticky ++ fresh
    val sizing = ConsensusPeerController.AdmissionSizing.from(consensusConfig, Core, selected.size)
    val evidence = SortedMap.from(
      (1L to 4L).map(n => ord(n) -> entry(retained.toSet, retained.toSet)) :+
        (ord(5L) -> entry((retained ++ sticky).toSet, (retained ++ sticky).toSet))
    )
    val (result, _) = choose(selected, evidence, sizing)

    expect.same(ProbationSlots, result.stickyProbationCandidateSize) &&
    expect.same(fresh.size, result.freshProbationCandidateSize) &&
    expect.same(sticky.toSet, result.probationAdmitted.toSet) &&
    expect(result.freshProbationStarved)
  }

  pureTest("the emergency bypass still fires below the configured floor") {
    val retained = List(peer(0), peer(1))
    val selected = retained ++ (2 until 12).map(peer)
    val evidence = SortedMap.from(
      (1L to 5L).map(n => ord(n) -> entry(retained.toSet, retained.toSet))
    )
    val sizing = ConsensusPeerController.AdmissionSizing.from(consensusConfig, Core, selected.size)
    val (result, _) = choose(selected, evidence, sizing)

    expect(!result.recentFilterApplied) &&
    expect.same(math.min(selected.size, Target), result.active.size)
  }
}
