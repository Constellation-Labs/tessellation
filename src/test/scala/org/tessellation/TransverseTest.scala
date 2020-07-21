package org.tessellation

import cats.data.NonEmptyList
import higherkindness.droste.Embed.drosteBasisForFix
import higherkindness.droste.data.list.{ListF, NilF}
import higherkindness.droste.data.{Fix, Mu, Nu}
import higherkindness.droste.scheme
import org.scalacheck.Prop.{forAll, _}
import org.scalacheck.Properties
import org.tessellation.schema.OldHom._
import org.tessellation.schema.MutuallyRecursive._
import org.tessellation.schema.{OldCocell, Context, OldHom}


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
    val fixed: Fix[OldHom[Int, *]] =
      Fix(OldCocell(1,
        Fix(OldCocell(2,
          Fix(OldCocell(3,
            Fix(Context(): OldHom[Int, Fix[OldHom[Int, *]]])))))))

    OldHom.toScalaList(fixed) ?= 1 :: 2 :: 3 :: Nil
  }

  property("Mu Hom -> List") = {
    val mu: Mu[OldHom[Int, *]] =
      Mu(OldCocell(1,
        Mu(OldCocell(2,
          Mu(OldCocell(3,
            Mu(Context(): OldHom[Int, Mu[OldHom[Int, *]]])))))))

    OldHom.toScalaList(mu) ?= 1 :: 2 :: 3 :: Nil
  }

  property("Nu Hom -> List") = {
    val nu: Nu[OldHom[Int, *]] =
      Nu(OldCocell(1,
        Nu(OldCocell(2,
          Nu(OldCocell(3,
            Nu(Context(): OldHom[Int, Nu[OldHom[Int, *]]])))))))

    OldHom.toScalaList(nu) ?= 1 :: 2 :: 3 :: Nil
  }

  property("rountrip Hom") = {
    val f = scheme.hylo(OldHom.toScalaListAlgebra[String], OldHom.fromScalaListCoalgebra[String])
    forAll((list: List[String]) => f(list) ?= list)
  }
}
