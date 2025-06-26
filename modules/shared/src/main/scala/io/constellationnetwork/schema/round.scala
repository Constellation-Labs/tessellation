package io.constellationnetwork.schema

import java.util.UUID

import cats.Show

import io.constellationnetwork.ext.derevo.ordering

import derevo.cats.{eqv, order}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object round {

  @derive(eqv, encoder, decoder, order, ordering)
  @newtype
  case class RoundId(value: UUID)

  object RoundId {
    val shortShow: Show[RoundId] = Show.show[RoundId](p => s"RoundId(${p.value.toString.take(5)})")
  }
}
