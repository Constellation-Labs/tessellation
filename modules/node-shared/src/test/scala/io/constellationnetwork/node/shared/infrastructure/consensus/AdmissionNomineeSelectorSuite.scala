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
    val expected = AdmissionNomineeSelector.select(candidates, Set.empty, entropy("parent"))
    val selections = candidates.permutations
      .take(120)
      .map { permutation =>
        AdmissionNomineeSelector.select(permutation ++ permutation.take(2), Set.empty, entropy("parent"))
      }
      .toList

    expect(expected.nonEmpty).and(forEach(selections)(selection => expect.same(expected, selection)))
  }

  test("committee, probation, and penalty exclusions are applied before ranking") {
    val first = AdmissionNomineeSelector.select(candidates, Set.empty, entropy("parent")).get
    val second = AdmissionNomineeSelector.select(candidates, Set(first), entropy("parent"))

    expect(second.nonEmpty).and(expect(!second.contains(first)))
  }

  test("round entropy rotates nomination rather than pinning the lowest PeerId") {
    val nominees = (1 to 32).flatMap { index =>
      AdmissionNomineeSelector.select(candidates, Set.empty, entropy(s"parent-$index"))
    }.toSet

    expect(nominees.size > 1)
  }

  test("empty/pre-upgrade candidate input yields no nominee") {
    expect.same(None, AdmissionNomineeSelector.select(List.empty, Set.empty, entropy("parent")))
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
