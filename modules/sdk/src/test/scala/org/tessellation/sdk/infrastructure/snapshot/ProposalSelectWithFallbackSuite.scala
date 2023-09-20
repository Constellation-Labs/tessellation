package org.tessellation.sdk.infrastructure.snapshot

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.applicative._
import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.sdk.infrastructure.consensus.declaration.Proposal
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosDouble
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ProposalSelectWithFallbackSuite extends SimpleIOSuite with Checkers {

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

  def mkMockSelectWithError(result: IO[List[(Hash, PosDouble)]]): ProposalSelect[IO] =
    (declarations: Map[PeerId, PeerDeclarations]) => result

  test("exceptions raised by proposal selects are not caught") {
    val result = IO.raiseError(new Exception("Raising an error."))
    val mockSelect = mkMockSelectWithError(result)
    val select = ProposalSelectWithFallback.make(mockSelect)

    select
      .score(declarations)
      .attempt
      .map(EitherT.fromEither(_))
      .flatMap(_.value.map { e =>
        expect.eql(true, e.isLeft)
      })
  }

  test("proposal selects are tried until a non-empty list is returned") {
    val result: IO[List[(Hash, PosDouble)]] = List.empty.pure
    val mockSelect = mkMockSelectWithError(result)
    val fallbackSelect = ProposalOccurrenceSelect.make()
    val select = ProposalSelectWithFallback.make(mockSelect, mockSelect, fallbackSelect)

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 3.0),
      (Hash("hashB"), 2.0),
      (Hash("hashA"), 1.0)
    )

    select
      .score(declarations)
      .map { r =>
        expect.eql(true, expected.diff(r).isEmpty)
      }
  }

  test("an empty list is returned if no non-empty list are encountered") {
    val result: IO[List[(Hash, PosDouble)]] = List.empty.pure
    val mockSelect = mkMockSelectWithError(result)
    val select = ProposalSelectWithFallback.make(mockSelect, mockSelect)

    val expected: List[(Hash, PosDouble)] = List.empty

    select
      .score(declarations)
      .map { r =>
        expect.eql(true, expected.diff(r).isEmpty)
      }
  }
}
