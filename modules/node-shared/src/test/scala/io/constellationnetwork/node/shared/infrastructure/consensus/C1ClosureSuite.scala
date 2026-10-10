package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.FunSuite

/** Metamorphic regression for forensic finding C-1.
  *
  * Two histories share the same frozen committee but differ in everything a leader can report: who "responded" (false omission of honest
  * members, false inclusion of an absent colluder), the derived recent-signer windows, carried tiers, quality, scores, view-change blame,
  * and self-health hints. After the single-voting-set change none of that may alter who votes, the quorum, or who leads at any view.
  */
object C1ClosureSuite extends FunSuite {

  private def peer(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val committee: List[PeerId] = (1 to 7).map(peer).toList
  private val colluders: Set[PeerId] = Set(peer(1), peer(2))
  private val honest: Set[PeerId] = committee.toSet -- colluders
  private val absentColluder: PeerId = peer(2)

  private val config = ConsensusConfig(10.seconds, 10.seconds, 100L, 10.seconds, EventCutterConfig(1024, 1024))

  private def entry(signers: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(committee),
      completedSigners = SortedSet.from(signers),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  private def evidence(signersPerRound: Long => Set[PeerId]): SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    SortedMap.from((10L to 19L).map(o => ord(o) -> entry(signersPerRound(o))))

  // Truthful history: everyone signs every round.
  private val truthful = evidence(_ => committee.toSet)

  // Captured history: colluding leaders report only themselves (false omission of every honest member) and keep the
  // absent colluder "present" (false inclusion).
  private val captured = evidence(_ => colluders)

  private val poisonedCarried = CertifiedRoundCommitteeProjector.CarriedControllerState(
    activeScores = honest.iterator.map(_ -> 0).toMap ++ colluders.iterator.map(_ -> 150),
    peerQuality = honest.iterator.map(_ -> (0, 9)).toMap ++ colluders.iterator.map(_ -> (9, 9)),
    peerTiers = SortedMap.from(honest.iterator.map(_ -> TierTransitions.Tier1) ++ colluders.iterator.map(_ -> TierTransitions.Core)),
    viewChanges = honest.iterator.map(_ -> 9L).toMap,
    selfHealth = honest.iterator.map(_ -> (SelfHealthHint.Critical: SelfHealthHint)).toMap
  )

  private def projectWith(
    history: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    carried: CertifiedRoundCommitteeProjector.CarriedControllerState
  ): CertifiedRoundCommitteeProjector.Projection =
    CertifiedRoundCommitteeProjector.project(
      key = ord(20L),
      selectedFacilitators = committee,
      recentSigners = history.map { case (o, e) => o -> e.completedSigners },
      controllerEvidence = history,
      carried = carried,
      config = config,
      coreCommitteeSize = 3,
      forcedTier1Peers = Set.empty
    )

  private val baseline = projectWith(truthful, CertifiedRoundCommitteeProjector.CarriedControllerState.empty)
  private val attacked = projectWith(captured, poisonedCarried)

  test("leader-reported content cannot change who votes") {
    expect
      .same(committee.sorted, baseline.committees.core)
      .and(expect.same(baseline.committees.core, attacked.committees.core))
      .and(expect.same(List.empty[PeerId], attacked.committees.tier1))
      .and(expect.same(baseline.signingFacilitators.sorted, attacked.signingFacilitators.sorted))
      .and(expect(honest.subsetOf(attacked.committees.core.toSet), "every falsely omitted honest member keeps its vote"))
  }

  test("leader-reported content cannot change the certificate quorum") {
    val q = (n: Int) => QuorumPolicy.fromFraction(n, config.quorumThresholdFraction)
    expect.same(q(baseline.committees.core.size), q(attacked.committees.core.size))
  }

  test("leader-reported content cannot change who leads at any view") {
    val views = 0L until (2L * committee.size)
    val baselineLeaders = views.map(v => LeaderSchedule.leader(baseline.committees.core, 20L, v))
    val attackedLeaders = views.map(v => LeaderSchedule.leader(attacked.committees.core, 20L, v))
    expect
      .same(baselineLeaders, attackedLeaders)
      .and(expect(honest.subsetOf(attackedLeaders.toSet), "honest members keep their leader turns"))
  }

  test("a falsely included absent colluder gains nothing beyond its ordinary seat") {
    val attackedLeaders = (0L until committee.size.toLong).map(v => LeaderSchedule.leader(attacked.committees.core, 20L, v))
    expect.same(1, attackedLeaders.count(_ == absentColluder))
  }

  test("newly admitted members vote immediately instead of being forced into a non-voting tier") {
    val admitted = peer(7)
    val withAdmission = CertifiedRoundCommitteeProjector.project(
      key = ord(20L),
      selectedFacilitators = committee,
      recentSigners = SortedMap.empty,
      controllerEvidence = SortedMap.empty,
      carried = CertifiedRoundCommitteeProjector.CarriedControllerState.empty,
      config = config,
      coreCommitteeSize = 3,
      forcedTier1Peers = Set(admitted)
    )
    expect(withAdmission.committees.core.contains(admitted))
  }
}
