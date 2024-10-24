package io.constellationnetwork.schema

import cats.syntax.order._

import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.syntax.boolean._

import weaver._
import weaver.scalacheck.Checkers

object RumorOrdinalSuite extends SimpleIOSuite with Checkers {

  test("if generation differs then ordinal comparison is the same as generation comparison") {
    forall { (a: Ordinal, b: Ordinal) =>
      expect(a.generation != b.generation ==> (a.generation.comparison(b.generation) == a.comparison(b)))
    }
  }

  test("if generation is the same then ordinal comparison is the same as counter comparison") {
    forall { (a: Ordinal, b: Ordinal) =>
      expect((a.generation == b.generation) ==> (a.counter.comparison(b.counter) == a.comparison(b)))
    }
  }

}
