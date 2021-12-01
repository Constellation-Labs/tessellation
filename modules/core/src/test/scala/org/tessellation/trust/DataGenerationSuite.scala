package org.tessellation.trust

import cats.effect.IO
import cats.effect.std.Random

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DataGenerationSuite extends SimpleIOSuite with Checkers {
  test("Data generation has values populated") {
    val numNodes = 30

    val generator = Random.scalaUtilRandom[IO].map { implicit rnd =>
      new DataGenerator[IO]
    }
    generator
      .flatMap(_.generateData(numNodes = numNodes))
      .map(_.head.edges)
      .map(edges => expect.all(edges.exists(_.trust != 0), edges.nonEmpty, edges.length < numNodes))
  }
}
