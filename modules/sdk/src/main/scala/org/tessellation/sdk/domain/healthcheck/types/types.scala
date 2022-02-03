package org.tessellation.sdk.domain.healthcheck.types

import java.util.UUID

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object types {

  @derive(encoder, decoder)
  @newtype
  final case class RoundId(value: UUID)
}
