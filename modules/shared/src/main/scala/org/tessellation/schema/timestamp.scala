package org.tessellation.schema

import derevo.circe.magnolia.encoder
import derevo.derive
import io.estatico.newtype.macros.newtype

object timestamp {
  @derive(encoder)
  @newtype
  case class SnapshotTimestamp(millisSinceEpoch: Long)
}
