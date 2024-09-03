package io.constellationnetwork.node.shared.infrastructure.snapshot

import io.constellationnetwork.node.shared.infrastructure.snapshot.WeightedSampling.{ProbabilityRefinement, Weight}

import eu.timepit.refined.auto._
import eu.timepit.refined.cats.refTypeShow
import eu.timepit.refined.refineV
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object WeightedSamplingSuite extends SimpleIOSuite with Checkers {

  val distribution: List[(String, Weight)] = List(
    "a" -> 0.1,
    "b" -> 0.5,
    "c" -> 0.3
  )
  val distributionSum: Double = distribution.foldLeft(0.0)(_ + _._2)

  test("select a because the chosen probability falls within a's probability range.") {
    val lowerBound = 0.0
    val upperBound = 0.1 / distributionSum

    val gen = Gen
      .chooseNum(lowerBound, upperBound - 1e-10)
      .map(p => refineV[ProbabilityRefinement](p).toOption.get)

    forall(gen) { chosenP =>
      WeightedSampling.sample(chosenP)(distribution).map(expect.eql("a", _))
    }
  }

  test("select b because the chosen probability falls within b's probability range.") {
    val lowerBound = 0.1 / distributionSum
    val upperBound = 0.6 / distributionSum

    val gen = Gen
      .chooseNum(lowerBound + 1e-10, upperBound - 1e-10)
      .map(p => refineV[ProbabilityRefinement](p).toOption.get)

    forall(gen) { chosenP =>
      WeightedSampling.sample(chosenP)(distribution).map(expect.eql("b", _))
    }
  }

  test("select c because the chosen probability falls within c's probability range.") {
    val lowerBound = 0.6 / distributionSum
    val upperBound = 1.0

    val gen = Gen
      .chooseNum(lowerBound + 1e-10, upperBound - 1e-10)
      .map(p => refineV[ProbabilityRefinement](p).toOption.get)

    forall(gen) { chosenP =>
      WeightedSampling.sample(chosenP)(distribution).map(expect.eql("c", _))
    }
  }

}
