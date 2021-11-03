package org.tesselation.crypto

import cats.Eq
import cats.data.NonEmptyList
import cats.kernel.laws.discipline.SemigroupTests

import org.tesselation.schema.ID.Id
import org.tesselation.security.signature.Signed
import org.tesselation.security.signature.signature.{Signature, SignatureProof}

import org.scalacheck.{Arbitrary, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

object SignedSuite extends FunSuite with Discipline {
  implicit val eq: Eq[Signed[String]] = _ == _

  implicit val arbitrarySigned: Arbitrary[Signed[String]] = Arbitrary(
    Gen.alphaLowerStr.map(str => Signed(str, NonEmptyList.one(SignatureProof(Id(str), Signature(str)))))
  )

  checkAll("CellMonoidLaw", SemigroupTests[Signed[String]].semigroup)
}
