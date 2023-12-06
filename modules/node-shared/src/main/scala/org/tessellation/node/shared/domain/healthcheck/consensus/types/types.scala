package org.tessellation.node.shared.domain.healthcheck.consensus.types

import java.util.UUID

import cats.Show
import cats.syntax.show._

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object types {

  @derive(encoder, decoder)
  @newtype
  final case class RoundId(value: UUID)

  object RoundId {
    implicit def showInstance: Show[RoundId] = r => s"RoundId(${r.value.show.take(8)}})"
  }
}
