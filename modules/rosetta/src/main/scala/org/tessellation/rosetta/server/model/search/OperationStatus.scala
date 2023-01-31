package org.tessellation.rosetta.server.model.search

import scala.util.Try

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum.{Enum, EnumEntry}
import io.circe.{Decoder, Encoder}

@derive(eqv, show)
sealed trait OperationStatus extends EnumEntry

object OperationStatus extends Enum[OperationStatus] {
  val values = findValues

  case object Accepted extends OperationStatus
  case object Rejected extends OperationStatus
  case object Unknown extends OperationStatus
  case object Pending extends OperationStatus
}

trait OperationStatusCodecs {
  implicit val encode: Encoder[OperationStatus] =
    Encoder.encodeString.contramap[OperationStatus](_.toString)
  implicit val decode: Decoder[OperationStatus] =
    Decoder.decodeString.emapTry(name => Try(OperationStatus.withName(name.toLowerCase.capitalize)))
}
