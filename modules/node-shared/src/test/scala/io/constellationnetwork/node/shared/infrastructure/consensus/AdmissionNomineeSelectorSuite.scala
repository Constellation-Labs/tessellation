package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import io.circe.syntax._
import weaver.FunSuite

object AdmissionNomineeSelectorSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def entropy(label: String): Hash = Hash.fromBytes(label.getBytes("UTF-8"))

  private val candidates = List(peer('1'), peer('2'), peer('3'), peer('4'), peer('5'), peer('6'))

  test("input order and duplicates cannot change the canonical nominee") {
    val expected = AdmissionNomineeSelector.select(candidates, Set.empty, 7L)
    val selections = candidates.permutations
      .take(120)
      .map { permutation =>
        AdmissionNomineeSelector.select(permutation ++ permutation.take(2), Set.empty, 7L)
      }
      .toList

    expect(expected.nonEmpty).and(forEach(selections)(selection => expect.same(expected, selection)))
  }

  test("committee, probation, and penalty exclusions are applied before ranking") {
    val first = AdmissionNomineeSelector.select(candidates, Set.empty, 7L).get
    val second = AdmissionNomineeSelector.select(candidates, Set(first), 7L)

    expect(second.nonEmpty).and(expect(!second.contains(first)))
  }

  test("consecutive opportunities nominate every candidate exactly once per cycle") {
    val nominees = (0L until candidates.size.toLong).flatMap(index => AdmissionNomineeSelector.select(candidates, Set.empty, index))
    expect.same(candidates.toSet, nominees.toSet).and(expect.same(candidates.size, nominees.distinct.size))
  }

  test("cadence aliasing cannot starve a candidate when candidate count shares a factor with the cadence") {
    // 5 candidates, cadence 5: nominations at rounds 5k - 1 must still advance one candidate per opportunity.
    val five = candidates.take(5)
    val cadence = 5
    val nominationRounds = (1 to 10).map(k => (k * cadence - 1).toLong)
    val nominees = nominationRounds.flatMap { round =>
      AdmissionNomineeSelector.select(five, Set.empty, DeterministicRotation.nominationIndex(round, cadence))
    }
    expect.same(five.toSet, nominees.take(five.size).toSet)
  }

  test("a permanently unqualified candidate does not block the others") {
    val stuck = candidates.head
    val nominees = (0L until (2L * candidates.size)).flatMap(index => AdmissionNomineeSelector.select(candidates, Set.empty, index))
    expect.same(candidates.toSet - stuck, nominees.filterNot(_ == stuck).toSet)
  }

  test("empty/pre-upgrade candidate input yields no nominee") {
    expect.same(None, AdmissionNomineeSelector.select(List.empty, Set.empty, 0L))
  }

  test("Proposal carries the nominee and decodes an old payload without the field") {
    import declaration.Proposal

    val nominee = candidates.head
    val proposal = Proposal(
      entropy("artifact"),
      entropy("facilitators"),
      entropy("parent"),
      view = 0L,
      vcc = None,
      admissionNominee = Some(nominee)
    )
    val encoded = proposal.asJson
    val legacyEncoded = encoded.mapObject(_.remove("admissionNominee"))

    expect.same(Right(proposal), encoded.as[Proposal]) &&
    expect.same(Right(proposal.copy(admissionNominee = None)), legacyEncoded.as[Proposal])
  }
}
