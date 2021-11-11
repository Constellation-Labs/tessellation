package org.tesselation.trust

import org.tesselation.infrastructure.trust.{SelfAvoidingWalk, TrustEdge, TrustNode}

import weaver.FunSuite
import weaver.scalacheck.Checkers

object TrustCalculationSuite extends FunSuite with Checkers {
  test("Basic small node network has transitive behavior") {

    val scores = SelfAvoidingWalk.runWalkFeedbackUpdateSingleNode(
      0,
      List(
        TrustNode(0, 0, 0, List(TrustEdge(0, 1, 0.9, isLabel = true), TrustEdge(0, 2, 0.5, isLabel = true))),
        TrustNode(1, 0, 0, List(TrustEdge(1, 0, 0.7, isLabel = true), TrustEdge(1, 3, 0.5, isLabel = true))),
        TrustNode(2, 0, 0, List(TrustEdge(2, 1, 0.9, isLabel = true), TrustEdge(2, 0, 0.5, isLabel = true))),
        TrustNode(3, 0, 0, List(TrustEdge(3, 1, 0.9, isLabel = true), TrustEdge(3, 1, 0.5, isLabel = true)))
      )
    )
    val nonZeroTransitive = scores.edges.find(_.dst == 3).get.trust > 0
    val originalScores = scores.edges.find(_.dst == 1).get.trust == 0.9
    expect.all(nonZeroTransitive, originalScores)
  }
}
