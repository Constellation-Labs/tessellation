package org.tessellation.ext.cats.effect

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.eq._
import cats.syntax.parallel._
import cats.syntax.semigroup._

import org.tessellation.ext.cats.effect.Lock.Released

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object RefLockOpsSuite extends SimpleIOSuite with Checkers {

  test("side effects are evaluated only on successful updates") {
    for {
      iR <- Ref.of[IO, Lock[Int]](Released(0))
      jR <- Ref.of[IO, Int](0)

      f = iR.tryUpdateL { i =>
        jR.update(_ |+| 1) // this is a side effect
          .map(_ => i |+| 1)
      }

      n = 1000
      result <- f.parReplicateA(n)
      count = result.count(identity)

      i <- iR.getL
      j <- jR.get
    } yield expect.all(count === i, count === j, count >= 1, count <= n)
  }

}
