package org.tessellation

import cats.data.NonEmptyList
import higherkindness.droste.Embed.drosteBasisForFix
import higherkindness.droste.data.list.{ListF, NilF}
import higherkindness.droste.data.{Fix, Mu, Nu}
import higherkindness.droste.scheme
import org.scalacheck.Prop.{forAll, _}
import org.scalacheck.Properties
import org.tessellation.schema.MutuallyRecursive._
import org.tessellation.schema.{Cell, Context, Hom, Cell2}

/*
object TransverseTest extends Properties("TransverseTest") {
  property("empty list to Hom fails") =
    toHomF(Fix[ListF[Int, *]](NilF)) ?= None

  property("round trip Nil") = {
    forAll { (nel: NonEmptyList[Int]) =>
      val listF = ListF.fromScalaList(nel.toList)(drosteBasisForFix)
      toHomF(listF).map(fromHomF) ?= Some(listF)
    }
  }

  property("Fix Hom -> List") = {
    val fixed: Fix[Hom[Int, *]] =
      Fix(Cell2(1,
        Fix(Cell2(2,
          Fix(Cell2(3,
            Fix(Context(): Hom[Int, Fix[Hom[Int, *]]])))))))

    Hom.toScalaList(fixed) ?= 1 :: 2 :: 3 :: Nil
  }
  // proof of least fixed point
  property("Mu Hom -> List") = {
    val mu: Mu[Hom[Int, *]] =
      Mu(Cell2(1,
        Mu(Cell2(2,
          Mu(Cell2(3,
            Mu(Context(): Hom[Int, Mu[Hom[Int, *]]])))))))

    Hom.toScalaList(mu) ?= 1 :: 2 :: 3 :: Nil
  }

  // proof of greatest fixed point
  property("Nu Hom -> List") = {
    val nu: Nu[Hom[Int, *]] =
      Nu(Cell2(1,
        Nu(Cell2(2,
          Nu(Cell2(3,
            Nu(Context(): Hom[Int, Nu[Hom[Int, *]]])))))))

    Hom.toScalaList(nu) ?= 1 :: 2 :: 3 :: Nil
  }

  property("rountrip Hom") = {
    val f = scheme.hylo(Hom.toScalaListAlgebra[String], Hom.fromScalaListCoalgebra[String])
    forAll((list: List[String]) => f(list) ?= list)
  }

//  def mu[A](hom: Hom[A, _]) = Mu(hom)
}
 */
