package io.constellationnetwork.node.shared.domain.seedlist

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen

object generators {

  val terminatedOrdinalRangeGen: Gen[(NonNegLong, NonNegLong, String)] = for {
    a <- Gen.chooseNum(0L, Long.MaxValue).map(NonNegLong.unsafeFrom)
    b <- Gen.chooseNum[Long](a, Long.MaxValue).map(NonNegLong.unsafeFrom)
  } yield (a, b, s"$a-$b")

}
