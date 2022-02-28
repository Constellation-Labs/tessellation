package org.tessellation.ext.cats.syntax

import scala.annotation.tailrec

import org.tessellation.ext.cats.kernel.PartialPrevious

import _root_.cats.kernel.{PartialPrevious => catsPartialPrevious}
import _root_.cats.syntax.eq._
import _root_.cats.syntax.option._
import _root_.cats.syntax.semigroup._
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

trait PartialPreviousSyntax {
  implicit def catsSyntaxPartialPrevious[A: catsPartialPrevious](a: A): PartialPreviousOps[A] =
    new PartialPreviousOps[A](a)
}

final class PartialPreviousOps[A: catsPartialPrevious](a: A) {
  def partialPrevious = PartialPrevious[A].partialPrevious(a)

  def partialPreviousN(n: NonNegLong): Option[A] = {
    @tailrec
    def go(acc: Option[A], k: NonNegLong): Option[A] =
      acc match {
        case Some(ac) if k === n => ac.some
        case Some(ac)            => go(PartialPrevious[A].partialPrevious(ac), k |+| NonNegLong(1L))
        case None                => none
      }

    go(a.some, NonNegLong.MinValue)
  }
}
