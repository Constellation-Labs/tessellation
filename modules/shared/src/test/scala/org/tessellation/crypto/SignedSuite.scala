package org.tessellation.crypto

import cats.Eq
import cats.data.NonEmptyList
import cats.kernel.laws.discipline.SemigroupTests

import org.tessellation.schema.ID.Id
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

import org.scalacheck.{Arbitrary, Gen}
import weaver.FunSuite
import weaver.discipline.Discipline

object SignedSuite extends FunSuite with Discipline {
  implicit val eq: Eq[Signed[Hex]] = _ == _

  implicit val arbitrarySigned: Arbitrary[Signed[Hex]] = Arbitrary(
    Gen.alphaLowerStr.map(Hex(_)).map(str => Signed(str, NonEmptyList.one(SignatureProof(Id(str), Signature(str)))))
  )

  checkAll("CellMonoidLaw", SemigroupTests[Signed[Hex]].semigroup)
}
