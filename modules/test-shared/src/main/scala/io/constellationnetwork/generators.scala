package io.constellationnetwork

import org.scalacheck.Gen

object generators {

  val nonEmptyStringGen: Gen[String] =
    Gen.chooseNum(21, 40).flatMap { n =>
      Gen.buildableOfN[String, Char](n, Gen.alphaChar)
    }

  def nesGen[A](f: String => A): Gen[A] =
    nonEmptyStringGen.map(f)

}
