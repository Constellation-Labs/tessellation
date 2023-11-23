package org.tessellation.dag.l0.trust

import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.applicative._

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DataGenerationSuite extends SimpleIOSuite with Checkers {
  test("Data generation has values populated") {
    val numNodes = 30

    val generator = Random.scalaUtilRandom[IO].map { implicit rnd =>
      new DataGenerator[IO]
    }
    ignore("Non-deterministic").unlessA(false).flatMap { _ =>
      generator
        .flatMap(_.generateData(numNodes = numNodes))
        .map(_.head.edges)
        .map(edges => expect.all(edges.exists(_.trust != 0), edges.nonEmpty, edges.length < numNodes))
    }
  }
}
