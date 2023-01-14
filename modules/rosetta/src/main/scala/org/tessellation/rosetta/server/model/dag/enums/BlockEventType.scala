package org.tessellation.rosetta.server.model.dag.enums

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum.{Enum, EnumEntry}
import io.circe.Encoder

@derive(eqv, show)
sealed trait BlockEventType extends EnumEntry

object BlockEventType extends Enum[BlockEventType] with BlockEventTypeEncoder {
  val values = findValues

  case object BlockAdded extends BlockEventType
  case object BlockRemoved extends BlockEventType
}

trait BlockEventTypeEncoder {
  implicit val encode: Encoder[BlockEventType] =
    Encoder.encodeString.contramap[BlockEventType](_ match {
      case BlockEventType.BlockAdded   => "block_added"
      case BlockEventType.BlockRemoved => "block_removed"
    })
}
