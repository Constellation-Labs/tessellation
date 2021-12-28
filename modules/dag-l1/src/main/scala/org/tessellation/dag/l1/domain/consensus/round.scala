package org.tessellation.dag.l1.domain.consensus

import java.util.UUID

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object round {

  @derive(encoder, decoder)
  @newtype
  case class RoundId(value: UUID)
}
