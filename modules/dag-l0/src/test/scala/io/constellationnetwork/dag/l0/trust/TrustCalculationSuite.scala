package io.constellationnetwork.dag.l0.trust

import io.constellationnetwork.dag.l0.infrastructure.trust._

import weaver.FunSuite
import weaver.scalacheck.Checkers

object TrustCalculationSuite extends FunSuite with Checkers {

  val smallTestNetwork = List(
    TrustNode(0, 0, 0, List(TrustEdge(0, 1, 0.9, isLabel = true), TrustEdge(0, 2, 0.5, isLabel = true))),
    TrustNode(1, 0, 0, List(TrustEdge(1, 0, 0.7, isLabel = true), TrustEdge(1, 3, 0.5, isLabel = true))),
    TrustNode(2, 0, 0, List(TrustEdge(2, 1, 0.9, isLabel = true), TrustEdge(2, 0, 0.5, isLabel = true))),
    TrustNode(3, 0, 0, List(TrustEdge(3, 1, 0.9, isLabel = true), TrustEdge(3, 1, 0.5, isLabel = true)))
  )

  test("Basic small node network has transitive behavior") {
    val scores = SelfAvoidingWalk.runWalkFeedbackUpdateSingleNode(
      0,
      smallTestNetwork
    )
    val nonZeroTransitive = scores.edges.find(_.dst == 3).get.trust > 0
    val originalScores = scores.edges.find(_.dst == 1).get.trust == 0.9
    expect.all(nonZeroTransitive, originalScores)
  }
  test("DATT scores calculate basic network properly") {
    val dattNetwork = Map(
      0 -> Map(
        1 -> 0.1,
        2 -> 0.9,
        3 -> 0.8,
        4 -> 0.5
      ),
      1 -> Map(5 -> 0.5), // 5 is the weighted node here -- this contributes *.1*.5
      2 -> Map(6 -> 0.9), // 6 is an isolated non-weighting edge
      3 -> Map(5 -> 0.3), // weighting -- 0.8 * .3 = 0.24
      4 -> Map(5 -> 0.5), // 0.5 * 0.5 = 0.25
      // Three weighting values to node 5 -> yielding 0.1 ( 0.05), .8 (.24), .5 (.25)
      5 -> Map.empty[Int, Double],
      6 -> Map.empty[Int, Double]
    )
    val result = DATT.calculate(
      dattNetwork,
      0
    )
    expect.all(result(5) == 0.22999999999999998, result(6) == 0.81)
  }
}
