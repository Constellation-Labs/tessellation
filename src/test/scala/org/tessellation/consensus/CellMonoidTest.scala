package org.tessellation.consensus

import cats.Applicative
import cats.effect.IO
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.mockito.cats.IdiomaticMockitoCats
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.schema.Cell.NullData
import org.tessellation.schema.{Cell, StackF, Ω}


case class SampleData() extends Ω
case class SampleOutput() extends Ω

class CellMonoidTest extends AnyFreeSpec
  with IdiomaticMockito
  with IdiomaticMockitoCats
  with Matchers
  with ArgumentMatchersSugar
  with BeforeAndAfter {

  "empty" - {
    val M = Cell.cellMonoid[IO, StackF]

    "creates empty" in {
      val empty = M.empty[(Int, Int)]
      empty.data.isInstanceOf[NullData] shouldBe true
    }

    "empty hylo does nothing" in {
      val empty = M.empty[(Int, Int)]
      val hyloResult = empty.hyloM(_ => (1, 1))
      println(hyloResult)
    }
  }

}