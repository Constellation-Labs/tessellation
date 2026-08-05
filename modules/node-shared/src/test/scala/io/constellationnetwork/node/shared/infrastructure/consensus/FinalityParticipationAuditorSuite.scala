package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object FinalityParticipationAuditorSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def entropy(label: String): Hash = Hash.fromBytes(label.getBytes("UTF-8"))

  private val self = peer('1')
  private val core = Set(self, peer('2'), peer('3'))
  private val tier1 = Set(peer('4'), peer('5'), peer('6'), peer('7'))
  private val parentCommittee = core ++ Set(peer('4'), peer('5'), peer('6'))

  private def observe(
    parent: String,
    ordinal: Long,
    signers: Set[PeerId],
    previous: FinalityParticipationAuditor.MissHistory = FinalityParticipationAuditor.MissHistory.empty,
    observer: PeerId = self,
    inBootstrap: Boolean = false
  ): FinalityParticipationAuditor.Observation =
    FinalityParticipationAuditor.observe(
      observer,
      core,
      tier1,
      parentCommittee,
      signers,
      ordinal,
      entropy(parent),
      inBootstrap,
      previous
    )

  test("target is permutation-independent and restricted to peers seated in the parent") {
    val expected = FinalityParticipationAuditor.selectTarget(tier1, parentCommittee, entropy("parent"))
    val selections = tier1.toList.permutations
      .take(24)
      .map { permutation =>
        FinalityParticipationAuditor.selectTarget(permutation.toSet, parentCommittee, entropy("parent"))
      }
      .toList

    expect(expected.exists(parentCommittee.contains)) &&
    expect(!expected.contains(peer('7'))) &&
    forEach(selections)(selection => expect.same(expected, selection))
  }

  test("all auditable Tier-1 misses are tracked and a vote requires three consecutive misses") {
    val first = observe("parent-1", 1L, core)
    val second = observe("parent-2", 2L, core, first.history)
    val third = observe("parent-3", 3L, core, second.history)

    expect.same(Set(peer('4'), peer('5'), peer('6')), first.history.consecutiveMisses.keySet) &&
    expect(first.history.consecutiveMisses.values.forall(_ == 1)) &&
    expect(first.decision.exists(d => d.consecutiveMisses == 1 && !d.shouldVote)) &&
    expect(second.history.consecutiveMisses.values.forall(_ == 2)) &&
    expect(second.decision.exists(d => d.consecutiveMisses == 2 && !d.shouldVote)) &&
    expect(third.history.consecutiveMisses.values.forall(_ == TierTransitions.DemotionConsecutiveMisses)) &&
    expect(third.decision.exists(d => d.consecutiveMisses == TierTransitions.DemotionConsecutiveMisses && d.shouldVote))
  }

  test("any observed proof resets that peer while other Tier-1 streaks continue") {
    val first = observe("parent-1", 1L, core)
    val second = observe("parent-2", 2L, core + peer('4'), first.history)
    val third = observe("parent-3", 3L, core, second.history)

    expect.same(Some(0), second.history.consecutiveMisses.get(peer('4'))) &&
    expect.same(Some(2), second.history.consecutiveMisses.get(peer('5'))) &&
    expect.same(Some(1), third.history.consecutiveMisses.get(peer('4'))) &&
    expect.same(Some(3), third.history.consecutiveMisses.get(peer('5')))
  }

  test("re-observing the same parent is idempotent") {
    val first = observe("same-parent", 1L, core)
    val retry = observe("same-parent", 1L, core, first.history)

    expect.same(first.history, retry.history) &&
    expect.same(first.decision.map(_.consecutiveMisses), retry.decision.map(_.consecutiveMisses))
  }

  test("Tier-1 observers track history but do not emit audit decisions") {
    val observation = observe("parent", 1L, core, observer = peer('4'))

    expect.same(None, observation.decision) && expect(observation.history.consecutiveMisses.values.forall(_ == 1))
  }

  test("bootstrap and restart begin conservatively with no carried miss streak") {
    val first = observe("parent-1", 1L, core)
    val second = observe("parent-2", 2L, core, first.history)
    val bootstrap = observe("bootstrap", 3L, core, second.history, inBootstrap = true)
    val afterRestart = observe("parent-3", 3L, core, FinalityParticipationAuditor.MissHistory.empty)

    expect.same(FinalityParticipationAuditor.MissHistory.empty, bootstrap.history) &&
    expect.same(None, bootstrap.decision) &&
    expect(afterRestart.history.consecutiveMisses.values.forall(_ == 1)) &&
    expect(afterRestart.decision.forall(!_.shouldVote))
  }

  test("a skipped or rolled-back parent ordinal resets the consecutive sequence") {
    val first = observe("parent-1", 1L, core)
    val afterGap = observe("parent-3", 3L, core, first.history)
    val alternateSameOrdinal = observe("alternate-parent-3", 3L, core, afterGap.history)

    expect(afterGap.history.consecutiveMisses.values.forall(_ == 1)) &&
    expect(alternateSameOrdinal.history.consecutiveMisses.values.forall(_ == 1)) &&
    expect(alternateSameOrdinal.decision.forall(!_.shouldVote))
  }

  test("round entropy rotates the bounded audit target") {
    val targets = (1 to 32).flatMap { index =>
      FinalityParticipationAuditor.selectTarget(tier1, parentCommittee, entropy(s"parent-$index"))
    }.toSet

    expect(targets.size > 1)
  }
}
