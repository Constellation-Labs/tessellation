package org.tessellation.sdk.infrastructure.consensus

import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.Facility

import eu.timepit.refined.auto._
import weaver.FunSuite
import weaver.scalacheck.Checkers

object FacilitatorCalculatorSuite extends FunSuite with Checkers {

  val peerA = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
  )
  val peerB = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001"
  )
  val peerC = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002"
  )
  val peerD = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003"
  )
  val peerE = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004"
  )
  val peerF = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000005"
  )
  val peerG = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006"
  )
  val peerH = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000007"
  )
  val peerI = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008"
  )
  val peerJ = PeerId(
    "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000009"
  )
  val peerK = PeerId(
    "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000a"
  )

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
        List(peerA, peerB),
        Set(peerI, peerJ)
      )

    val expected = List(peerA, peerB, peerC, peerD, peerE, peerF, peerG, peerH)

    expect.same(expected, result)
  }
}
