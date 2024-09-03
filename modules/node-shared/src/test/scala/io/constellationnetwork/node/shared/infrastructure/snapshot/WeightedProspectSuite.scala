package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.std.Random

import io.constellationnetwork.schema.trust.TrustValueRefined

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object WeightedProspectSuite extends SimpleIOSuite with Checkers {

  val candidates: Map[String, TrustValueRefined] = Map(
    "a" -> 0.1,
    "b" -> 0.5,
    "c" -> 1.0
  )

  test("sampling more than the number of keys returns all keys") {
    Random.scalaUtilRandomSeedInt(0).flatMap { implicit r =>
      WeightedProspect.sample(candidates, 4).map { actual =>
        expect.eql(candidates.keySet.toList, actual)
      }
    }
  }

  test("non-positive entries are filtered out") {
    val extendedCandidates: Map[String, TrustValueRefined] = candidates ++ Map(
      "d" -> -1.0,
      "e" -> -0.5,
      "f" -> 0.0
    )

    Random.scalaUtilRandomSeedInt(0).flatMap { implicit r =>
      WeightedProspect.sample(extendedCandidates, 7).map { actual =>
        val expected = List("a", "b", "c")

        expect.eql(expected, actual)
      }
    }
  }

  test("sample a subset of candidate keys") {
    Random.scalaUtilRandomSeedInt(0).flatMap { implicit r =>
      WeightedProspect.sample(candidates, 2).map { actual =>
        val expected = List("c", "b")

        expect.eql(expected, actual)
      }
    }
  }

}
