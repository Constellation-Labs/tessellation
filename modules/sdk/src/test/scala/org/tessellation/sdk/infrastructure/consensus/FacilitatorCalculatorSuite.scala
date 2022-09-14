package org.tessellation.sdk.infrastructure.consensus

import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.Facility
import org.tessellation.security.hex.Hex

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
          peerB -> PeerDeclarations(
            facility = Facility(Map.empty, Set(peerC, peerD), None).some,
            proposal = None,
            signature = None
          ),
          peerC -> PeerDeclarations(
            facility = Facility(Map.empty, Set(peerE, peerF), None).some,
            proposal = None,
            signature = None
          ),
          peerD -> PeerDeclarations(
            facility = Facility(Map.empty, Set(peerE, peerF, peerG), None).some,
            proposal = None,
            signature = None
          ),
          peerG -> PeerDeclarations(
            facility = Facility(Map.empty, Set(peerH, peerI, peerJ, peerK), None).some,
            proposal = None,
            signature = None
          )
        ),
        Set(peerA, peerB),
        Set(peerI, peerJ)
      )

    val expected = Set(peerA, peerB, peerC, peerD, peerE, peerF, peerG, peerH)

    expect.same(expected, result)
  }
}
