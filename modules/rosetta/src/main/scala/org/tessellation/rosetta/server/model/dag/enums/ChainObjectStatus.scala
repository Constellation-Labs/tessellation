package org.tessellation.rosetta.server.model.dag.enums

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum.{Enum, EnumEntry}
import io.circe.Encoder

@derive(eqv, show)
sealed trait ChainObjectStatus extends EnumEntry

object ChainObjectStatus extends Enum[ChainObjectStatus] with ChainObjectStatusEncoder {
  case object Unknown extends ChainObjectStatus
  case object Pending extends ChainObjectStatus
  case object Accepted extends ChainObjectStatus

  override def values: IndexedSeq[ChainObjectStatus] = findValues
}

trait ChainObjectStatusEncoder {
  implicit val encode: Encoder[ChainObjectStatus] =
    Encoder.encodeString.contramap[ChainObjectStatus](_.toString)
}
