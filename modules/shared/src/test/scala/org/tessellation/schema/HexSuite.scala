package org.tessellation.schema

import cats.syntax.order._
import cats.{Eq, Show}

import org.tessellation.schema.hex._

import eu.timepit.refined.cats._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object HexSuite extends SimpleIOSuite with Checkers {

  implicit def arrayShow[A: Show]: Show[Array[A]] = a => a.iterator.map(Show[A].show(_)).mkString("Array(", ", ", ")")

  implicit def arrayEq[A: Eq]: Eq[Array[A]] = (x: Array[A], y: Array[A]) =>
    x.length === y.length && x.zip(y).forall { case (a, b) => a === b }

  test("hex -> bytes -> hex") {
    forall { (hex: HexString) =>
      expect(hex.toBytes.toHexString === hex)
    }
  }

  test("bytes -> hex -> bytes") {
    forall { (arr: Array[Byte]) =>
      expect.same(arr.toHexString.toBytes, arr)
    }
  }

}
