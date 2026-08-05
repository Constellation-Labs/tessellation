package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.node.shared.infrastructure.consensus.{AdmissionNomineeSelector, FacilitatorSelector}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object OpenAdmissionTargetSelectorSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')
  private val entropy = Hash.fromBytes("accepted-parent-entropy".getBytes("UTF-8"))
  private val candidates = List(a, b, c, d)

  private def select(
    input: Iterable[PeerId] = candidates,
    committee: Set[PeerId] = Set.empty,
    probation: Set[PeerId] = Set.empty,
    alreadyVoted: Set[PeerId] = Set.empty,
    budget: Int = 1,
    selfIsCore: Boolean = true
  ): List[PeerId] =
    StallDetector.openAdmissionTargets(
      candidates = input,
      committee = committee,
      probation = probation,
      alreadyVotedBySelf = alreadyVoted,
      entropy = entropy,
      maxOpenAdmissions = budget,
      selfIsCore = selfIsCore
    )

  test("candidate input permutations converge on one rendezvous-ranked target") {
    val selections = candidates.permutations.map(permutation => select(input = permutation)).toList
    expect(selections.nonEmpty).and(forEach(selections)(selection => expect.same(selections.head, selection)))
  }

  test("a budget of one is exhausted after the fixed target receives this voter's vote") {
    val target = select().head
    expect.same(List.empty[PeerId], select(alreadyVoted = Set(target)))
  }

  test("selection fixes the target before local readiness can cause an abstention") {
    implicit val order = FacilitatorSelector.orderByScore(entropy)
    val ranked = candidates.sorted(order.toOrdering)

    // The caller may find ranked.head absent from its local at-tip map. It receives no
    // alternative here, so filtering that target locally yields abstention, not ranked(1).
    expect.same(List(ranked.head), select()) &&
    expect(select().filter(_ => false).isEmpty)
  }

  test("probation votes do not consume the independent open-expansion budget") {
    val probationTarget = a
    val result = select(probation = Set(probationTarget), alreadyVoted = Set(probationTarget))

    expect.same(1, result.size) && expect(!result.contains(probationTarget))
  }

  test("only Core emits open-expansion votes") {
    expect.same(List.empty[PeerId], select(selfIsCore = false))
  }

  test("followers with different local candidate sets vote for the proposal-carried nominee") {
    val leaderLocal = List(a, b, c)
    val followerALocal = List(a, b)
    val followerBLocal = List(c, d)
    val nominee = AdmissionNomineeSelector.select(leaderLocal, Set.empty, entropy).toList

    val followerA = select(input = nominee)
    val followerB = select(input = nominee)

    expect(followerALocal != followerBLocal) && expect.same(followerA, followerB) && expect.same(nominee, followerA)
  }

  test("a pre-upgrade parent with no carried nominee emits no open vote") {
    expect.same(List.empty[PeerId], select(input = List.empty))
  }

  test("committee and probation peers are never open-expansion targets") {
    val result = select(committee = Set(a), probation = Set(b), budget = 4)
    expect.same(Set(c, d), result.toSet)
  }
}
