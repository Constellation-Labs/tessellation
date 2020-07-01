package org.tessellation.schema

import cats.data.NonEmptyList
import cats.implicits._
import higherkindness.droste.data.Fix
import higherkindness.droste.data.list.{ConsF, ListF, NilF}
import higherkindness.droste.{Trans, TransM, scheme}
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary

object MutuallyRecursive {
  def transListToHom[A]: TransM[Option, ListF[A, ?], Hom[A, ?], Fix[ListF[A, ?]]] = TransM {
    case ConsF(head, tail) =>
      Fix.un(tail) match {
        case NilF => Cell(head).some
        case _ => Cocell(head, tail).some
      }
    case NilF => None
  }

  def toHomF[A]: Fix[ListF[A, ?]] => Option[Fix[Hom[A, ?]]] =
    scheme.anaM(transListToHom[A].coalgebra)

  def transHomToList[A]: Trans[Hom[A, ?], ListF[A, ?], Fix[ListF[A, ?]]] = Trans {
    case Cocell(head, tail) => ConsF(head, tail)
    case Cell(last) => ConsF(last, Fix[ListF[A, ?]](NilF))
  }

  def fromHomF[A]: Fix[Hom[A, ?]] => Fix[ListF[A, ?]] =
    scheme.cata(transHomToList[A].algebra)

  implicit def arbitraryHOM[A: Arbitrary]: Arbitrary[NonEmptyList[A]] =
    Arbitrary(for {
      head <- arbitrary[A]
      tail <- arbitrary[List[A]]
    } yield NonEmptyList.of(head, tail: _*))
}