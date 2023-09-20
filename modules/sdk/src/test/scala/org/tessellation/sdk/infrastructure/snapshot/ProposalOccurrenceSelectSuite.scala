package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.sdk.infrastructure.consensus.declaration.Proposal
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosDouble
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ProposalOccurrenceSelectSuite extends SimpleIOSuite with Checkers {
  test("proposal hashes are scored by their occurrence") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashA"), Hash("facilitatorHashA")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("d")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("e")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("f")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      )
    )

    val select = ProposalOccurrenceSelect.make()

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 3.0),
      (Hash("hashB"), 2.0),
      (Hash("hashA"), 1.0)
    )

    select.score(declarations).map(actual => expect.eql(true, expected.diff(actual).isEmpty))
  }

  test("no proposals returns an empty list") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        none,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        none,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        none,
        none
      ),
      PeerId(Hex("d")) -> PeerDeclarations(
        none,
        none,
        none
      ),
      PeerId(Hex("e")) -> PeerDeclarations(
        none,
        none,
        none
      ),
      PeerId(Hex("f")) -> PeerDeclarations(
        none,
        none,
        none
      )
    )

    val select = ProposalOccurrenceSelect.make()

    val expected: List[(Hash, PosDouble)] = List.empty[(Hash, PosDouble)]

    select.score(declarations).map(actual => expect.eql(true, expected.diff(actual).isEmpty))
  }
}
