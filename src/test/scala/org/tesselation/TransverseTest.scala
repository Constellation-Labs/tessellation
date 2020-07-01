package org.tesselation

import cats.{Applicative, Traverse}
import cats.data.NonEmptyList
import cats.implicits._
import higherkindness.droste.data.Fix
import higherkindness.droste.data.list.{ConsF, ListF, NilF}
import higherkindness.droste.{Trans, TransM, scheme}
import higherkindness.droste.util.DefaultTraverse
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Properties}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop._
import org.tessellation.schema.{Cell, Cocell, Hom}


object TransverseTest extends Properties("TransDemo") {
  import MutuallyRecursive._

  import higherkindness.droste.Embed.drosteBasisForFix

  property("empty list to NelF fails") =
    toNelF(Fix[ListF[Int, ?]](NilF)) ?= None

  property("round trip NelF") = {
    forAll { (nel: NonEmptyList[Int]) =>
      val listF = ListF.fromScalaList(nel.toList)(drosteBasisForFix)
      toNelF(listF).map(fromNelF) ?= Some(listF)
    }
  }

}

object MutuallyRecursive {
  import higherkindness.droste.Project._

  implicit def drosteTraverseForNeListF[A]: Traverse[Hom[A, ?]] =
    new DefaultTraverse[Hom[A, ?]] {

      def traverse[F[_]: Applicative, B, C](fb: Hom[A, B])(f: B => F[C]): F[Hom[A, C]] =
        fb match {
          case Cocell(head, tail) => f(tail).map(Cocell(head, _))
          case Cell(value)      => (Cell(value): Hom[A, C]).pure[F]
        }
    }

  // converting a list to a non-empty list can fail, so we use TransM
  def transListToNeList[A]: TransM[Option, ListF[A, ?], Hom[A, ?], Fix[ListF[A, ?]]] = TransM {
    case ConsF(head, tail) =>
      Fix.un(tail) match {
        case NilF => Cell(head).some
        case _    => Cocell(head, tail).some
      }
    case NilF => None
  }

  def toNelF[A]: Fix[ListF[A, ?]] => Option[Fix[Hom[A, ?]]] =
    scheme.anaM(transListToNeList[A].coalgebra)

  // converting a non-empty list to a list can't fail, so we use Trans
  def transNeListToList[A]: Trans[Hom[A, ?], ListF[A, ?], Fix[ListF[A, ?]]] = Trans {
    case Cocell(head, tail) => ConsF(head, tail)
    case Cell(last)       => ConsF(last, Fix[ListF[A, ?]](NilF))
  }

  def fromNelF[A]: Fix[Hom[A, ?]] => Fix[ListF[A, ?]] =
    scheme.cata(transNeListToList[A].algebra)

  // misc

  implicit def arbitraryNEL[A: Arbitrary]: Arbitrary[NonEmptyList[A]] =
    Arbitrary(for {
      head <- arbitrary[A]
      tail <- arbitrary[List[A]]
    } yield NonEmptyList.of(head, tail: _*))

}
