package org.tessellation.sdk.domain.healthcheck.consensus.types

import java.util.UUID

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object types {

  @derive(encoder, decoder, show)
  @newtype
  final case class RoundId(value: UUID)
}
