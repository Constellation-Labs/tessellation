package io.constellationnetwork.ext.cats.syntax

import io.constellationnetwork.ext.cats.kernel._

import _root_.cats.kernel.{Next => catsNext}
import _root_.cats.syntax.eq._
import _root_.cats.syntax.semigroup._
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

trait NextSyntax {
  implicit def catsSyntaxNext[A: catsNext](a: A): NextOps[A] =
    new NextOps[A](a)
}

final class NextOps[A: catsNext](a: A) {
  def next: A = Next[A].next(a)

  def nextN(n: NonNegLong): A = {
    @scala.annotation.tailrec
    def go(acc: A, k: NonNegLong): A =
      if (k === n)
        acc
      else
        go(Next[A].next(acc), k |+| NonNegLong(1L))

    go(a, NonNegLong.MinValue)
  }
}
