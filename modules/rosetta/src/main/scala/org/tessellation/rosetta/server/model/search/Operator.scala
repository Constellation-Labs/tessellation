package org.tessellation.rosetta.server.model.search

import scala.util.Try

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum.{Enum, EnumEntry}
import io.circe.{Decoder, Encoder}

/** This enumeration is for internal use only and meant to mirror:
  *
  * https://www.rosetta-api.org/docs/models/Operator.html
  */

@derive(eqv, show)
sealed trait Operator extends EnumEntry

object Operator extends Enum[Operator] with OperatorCodecs {
  val values = findValues

  case object And extends Operator
  case object Or extends Operator
}

trait OperatorCodecs {
  implicit val encode: Encoder[Operator] =
    Encoder.encodeString.contramap[Operator](_.toString.toLowerCase)
  implicit val decode: Decoder[Operator] =
    Decoder.decodeString.emapTry(name => Try(Operator.withName(name.toLowerCase.capitalize)))
}
