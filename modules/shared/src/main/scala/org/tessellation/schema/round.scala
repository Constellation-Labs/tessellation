package org.tessellation.schema

import java.util.UUID

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object round {

  @derive(eqv, encoder, decoder)
  @newtype
  case class RoundId(value: UUID)
}
