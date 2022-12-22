package org.tessellation.sdk.infrastructure.consensus

import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.hex.Hex
import org.tessellation.sdk.infrastructure.consensus.declaration.Facility

import weaver.FunSuite
import weaver.scalacheck.Checkers

object FacilitatorCalculatorSuite extends FunSuite with Checkers {

  val peerA = PeerId(Hex("a"))
  val peerB = PeerId(Hex("b"))
  val peerC = PeerId(Hex("c"))
  val peerD = PeerId(Hex("d"))
  val peerE = PeerId(Hex("e"))
  val peerF = PeerId(Hex("f"))
  val peerG = PeerId(Hex("g"))
  val peerH = PeerId(Hex("h"))
  val peerI = PeerId(Hex("i"))
  val peerJ = PeerId(Hex("j"))
  val peerK = PeerId(Hex("k"))

  val seedlist: Option[Set[PeerId]] = Some(
    Set(peerA, peerB, peerC, peerD, peerE, peerF, peerG, peerH, peerI, peerJ)
  )

  test("calculate should correctly discover facilitators") {

    val result = FacilitatorCalculator
      .make(seedlist)
      .calculate(
        Map(
          peerB -> facilityDeclaration(Set(peerC, peerD)),
          peerC -> facilityDeclaration(Set(peerE, peerF)),
          peerD -> facilityDeclaration(Set(peerE, peerF, peerG)),
          peerG -> facilityDeclaration(Set(peerH, peerI, peerJ, peerK))
        ),
        List(peerA, peerB),
        Set(peerI, peerJ)
      )

    val expected = List(peerA, peerB, peerC, peerD, peerE, peerF, peerG, peerH)

    expect.same(expected, result)
  }

  private def facilityDeclaration(facilitators: Set[PeerId]) =
    PeerDeclarations(
      facility = Facility(Map.empty, facilitators, None, Hash.empty).some,
      proposal = None,
      signature = None
    )
}
