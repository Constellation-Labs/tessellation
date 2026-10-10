package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Id

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object CertifiedNextRoundProjectorSuite extends FunSuite {

  private def pid(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))

  private val committee = (1 to 4).map(pid).toList

  private def project(
    roundStart: List[PeerId] = committee,
    admitted: Set[PeerId] = Set.empty,
    evicted: Set[PeerId] = Set.empty,
    seedlist: Set[PeerId] = Set.empty,
    eligible: PeerId => Boolean = _ => true,
    cap: Option[Int] = None
  ): Either[String, CertifiedNextRoundProjector.Projection] =
    CertifiedNextRoundProjector.project[Id](
      roundStart,
      admitted,
      evicted,
      maxChanges = 1,
      seedlistPeerIds = seedlist,
      isContextEligible = (p: PeerId) => eligible(p),
      facilitatorSelector = FacilitatorSelector.make(cap)
    )

  test("an unchanged committee projects to itself with no exits") {
    val result = project()
    expect.same(Right(committee), result.map(_.selectedCommittee)).and(expect.same(Right(Nil), result.map(_.exits)))
  }

  test("a committee already above the cap keeps every incumbent") {
    expect.same(Right(committee), project(cap = Some(2)).map(_.selectedCommittee))
  }

  test("an admission that would grow past max(cap, |B|) is rejected") {
    val result = project(admitted = Set(pid(5)), cap = Some(4))
    expect(result.left.exists(_.startsWith("certified_membership_over_cap")))
  }

  test("an admission within the cap joins the successor") {
    expect.same(Right(committee :+ pid(5)), project(admitted = Set(pid(5)), cap = Some(5)).map(_.selectedCommittee))
  }

  test("an incumbent removed from the seedlist is an explicit deterministic exit") {
    val seedlist = committee.toSet - pid(2)
    val result = project(seedlist = seedlist)
    expect
      .same(Right(committee.filterNot(_ == pid(2))), result.map(_.selectedCommittee))
      .and(expect.same(Right(List(pid(2))), result.map(_.exits)))
  }

  test("an incumbent that lost collateral eligibility is an explicit deterministic exit") {
    val result = project(eligible = _ != pid(3))
    expect.same(Right(List(pid(3))), result.map(_.exits))
  }

  test("an ineligible certified admission invalidates the transition") {
    val result = project(admitted = Set(pid(5)), eligible = _ != pid(5))
    expect(result.left.exists(_.startsWith("certified_membership_admission_not_next_round_eligible")))
  }

  test("a successor with no eligible members is rejected") {
    expect.same(Left("certified_membership_empty_successor"), project(eligible = _ => false).map(_.selectedCommittee))
  }

  test("a certified replacement swaps one member for another") {
    val result = project(admitted = Set(pid(5)), evicted = Set(pid(1)))
    expect.same(Right(List(pid(2), pid(3), pid(4), pid(5))), result.map(_.selectedCommittee))
  }
}
