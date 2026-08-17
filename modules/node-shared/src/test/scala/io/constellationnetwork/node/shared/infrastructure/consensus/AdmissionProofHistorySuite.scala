package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object AdmissionProofHistorySuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))
  private def hash(label: String): Hash = Hash.fromBytes(label.getBytes("UTF-8"))

  private val peers = (1 to 12).map(peer).toList

  private def append(
    history: AdmissionProofHistory.History,
    ordinal: Long,
    signers: Set[PeerId],
    label: String = ""
  ): AdmissionProofHistory.History =
    AdmissionProofHistory.observe(history, ordinal, hash(s"$label-$ordinal"), signers)

  test("duplicate parents are idempotent and history stays bounded") {
    val first = append(AdmissionProofHistory.History.empty, 1L, peers.take(3).toSet)
    val duplicate = AdmissionProofHistory.observe(first, 1L, hash("-1"), peers.take(2).toSet)
    val second = append(duplicate, 2L, peers.take(3).toSet)
    val third = append(second, 3L, peers.take(3).toSet)
    val fourth = append(third, 4L, peers.take(3).toSet)

    expect.same(first, duplicate) &&
    expect.same(Vector(2L, 3L, 4L), fourth.parents.map(_.ordinal)) &&
    expect.same(AdmissionProofHistory.RequiredConsecutiveParents, fourth.depth)
  }

  test("ordinal gaps, rollback, and same-ordinal hash replacement reset history") {
    val first = append(AdmissionProofHistory.History.empty, 10L, peers.take(3).toSet)
    val second = append(first, 11L, peers.take(3).toSet)
    val gap = append(second, 13L, peers.take(3).toSet)
    val rollback = append(second, 9L, peers.take(3).toSet)
    val replacement = AdmissionProofHistory.observe(second, 11L, hash("replacement"), peers.take(3).toSet)

    expect.same(Vector(13L), gap.parents.map(_.ordinal)) &&
    expect.same(Vector(9L), rollback.parents.map(_.ordinal)) &&
    expect.same(Vector(11L), replacement.parents.map(_.ordinal)) &&
    expect.same(hash("replacement"), replacement.parents.head.hash)
  }

  test("three consecutive next-floor observations close the 3-to-4 oscillator") {
    val committee = peers.take(3).toSet
    val twoSigners = committee.take(2)
    val allThree = committee
    val oneSpike = append(
      append(append(AdmissionProofHistory.History.empty, 1L, twoSigners), 2L, twoSigners),
      3L,
      allThree
    )
    val sustained = append(append(append(AdmissionProofHistory.History.empty, 1L, allThree), 2L, allThree), 3L, allThree)
    val transient = AdmissionProofHistory.evaluate(oneSpike, committee, 2.0 / 3.0, additionalSeats = 1)
    val stable = AdmissionProofHistory.evaluate(sustained, committee, 2.0 / 3.0, additionalSeats = 1)

    expect.same(2, transient.currentFinalityFloor) &&
    expect.same(3, transient.nextFinalityFloor) &&
    expect.same(1, transient.qualifyingParents) &&
    expect(!transient.allowsAdmission) &&
    expect.same(3, stable.qualifyingParents) &&
    expect(stable.allowsAdmission)
  }

  test("rotating proof subsets pass when every parent independently supports the raised floor") {
    val committee = peers.take(6).toSet
    val rounds = List(
      Set(peers(0), peers(1), peers(2), peers(3), peers(4)),
      Set(peers(0), peers(1), peers(2), peers(3), peers(5)),
      Set(peers(0), peers(1), peers(2), peers(4), peers(5))
    )
    val history = rounds.zipWithIndex.foldLeft(AdmissionProofHistory.History.empty) {
      case (acc, (signers, index)) => append(acc, index.toLong + 1L, signers)
    }
    val result = AdmissionProofHistory.evaluate(history, committee, 2.0 / 3.0, additionalSeats = 1)

    expect.same(5, result.nextFinalityFloor) &&
    expect.same(3, result.qualifyingParents) &&
    expect(result.allowsAdmission)
  }

  test("four-to-five waits for four signers on every observed parent") {
    val committee = peers.take(4).toSet
    val threeHistory = (1L to 3L).foldLeft(AdmissionProofHistory.History.empty) { (acc, ordinal) =>
      append(acc, ordinal, committee.take(3))
    }
    val fourHistory = (1L to 3L).foldLeft(AdmissionProofHistory.History.empty) { (acc, ordinal) =>
      append(acc, ordinal, committee)
    }
    val blocked = AdmissionProofHistory.evaluate(threeHistory, committee, 2.0 / 3.0, additionalSeats = 1)
    val allowed = AdmissionProofHistory.evaluate(fourHistory, committee, 2.0 / 3.0, additionalSeats = 1)

    expect.same(3, blocked.currentFinalityFloor) &&
    expect.same(4, blocked.nextFinalityFloor) &&
    expect(!blocked.allowsAdmission) &&
    expect(allowed.allowsAdmission)
  }

  test("outsider proofs never create sustained headroom") {
    val committee = peers.take(3).toSet
    val outsiders = peers.slice(3, 6).toSet
    val observed = committee.take(2) ++ outsiders
    val history = (1L to 3L).foldLeft(AdmissionProofHistory.History.empty) { (acc, ordinal) =>
      append(acc, ordinal, observed)
    }
    val result = AdmissionProofHistory.evaluate(history, committee, 2.0 / 3.0, additionalSeats = 1)

    expect.same(0, result.qualifyingParents) && expect(!result.allowsAdmission)
  }

  test("floor-neutral admission remains allowed without a complete history") {
    val committee = peers.take(5).toSet
    val incomplete = append(AdmissionProofHistory.History.empty, 1L, committee.take(4))
    val result = AdmissionProofHistory.evaluate(incomplete, committee, 2.0 / 3.0, additionalSeats = 1)

    expect.same(4, result.currentFinalityFloor) &&
    expect.same(4, result.nextFinalityFloor) &&
    expect(!result.historyComplete) &&
    expect(result.allowsAdmission)
  }

  test("maximum admission batch determines the sustained floor") {
    val committee = peers.take(6).toSet
    val fiveSigners = committee.take(5)
    val allSix = committee
    val fiveHistory = (1L to 3L).foldLeft(AdmissionProofHistory.History.empty) { (acc, ordinal) =>
      append(acc, ordinal, fiveSigners)
    }
    val sixHistory = (1L to 3L).foldLeft(AdmissionProofHistory.History.empty) { (acc, ordinal) =>
      append(acc, ordinal, allSix)
    }
    val blocked = AdmissionProofHistory.evaluate(fiveHistory, committee, 2.0 / 3.0, additionalSeats = 2)
    val allowed = AdmissionProofHistory.evaluate(sixHistory, committee, 2.0 / 3.0, additionalSeats = 2)

    expect.same(6, blocked.nextFinalityFloor) &&
    expect(!blocked.allowsAdmission) &&
    expect(allowed.allowsAdmission)
  }
}
