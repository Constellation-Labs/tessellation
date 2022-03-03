package org.tessellation.ext.cats.effect

import _root_.cats.Eq
import _root_.cats.syntax.eq._

sealed trait OptionLock[A] {}

object OptionLock {
  implicit def eqv[A: Eq]: Eq[OptionLock[A]] =
    (la, lb) =>
      (la, lb) match {
        case (NoneAcquired(), NoneAcquired())   => true
        case (SomeAcquired(a), SomeAcquired(b)) => a === b
        case (SomeReleased(a), SomeReleased(b)) => a === b
        case _                                  => false
      }

  def noneAcquired[A]: OptionLock[A] = NoneAcquired[A]()

  def someAcquired[A](a: A): OptionLock[A] = SomeAcquired[A](a)
  def someReleased[A](a: A): OptionLock[A] = SomeReleased[A](a)

  case class NoneAcquired[A]() extends OptionLock[A]
  case class SomeAcquired[A](a: A) extends OptionLock[A]
  case class SomeReleased[A](a: A) extends OptionLock[A]
}
