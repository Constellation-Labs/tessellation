package org.tessellation.ext.cats.effect

import _root_.cats.Eq
import _root_.cats.syntax.eq._

sealed trait Lock[A] {}

object Lock {
  implicit def eqv[A: Eq]: Eq[Lock[A]] =
    (la, lb) =>
      (la, lb) match {
        case (Acquired(a), Acquired(b)) => a === b
        case (Released(a), Released(b)) => a === b
        case _                          => false
      }

  def acquired[A](a: A): Lock[A] = Acquired(a)
  def released[A](a: A): Lock[A] = Released(a)

  final case class Acquired[A](a: A) extends Lock[A]
  final case class Released[A](a: A) extends Lock[A]
}
