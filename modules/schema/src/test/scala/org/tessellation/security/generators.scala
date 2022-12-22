package org.tessellation.schema.security

import org.scalacheck.Gen

object generators {
  val base58CharGen: Gen[Char] = Gen.oneOf(Base58.alphabet)

  val base58StringGen: Gen[String] =
    Gen.chooseNum(21, 40).flatMap { n =>
      Gen.buildableOfN[String, Char](n, base58CharGen)
    }
}
