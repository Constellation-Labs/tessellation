package org.tessellation.schema

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.macros.newtype

object generation {
  @derive(arbitrary, decoder, encoder, order, show)
  @newtype
  case class Generation(value: PosLong)

  object Generation {
    val MinValue = Generation(PosLong.MinValue)
  }
}
