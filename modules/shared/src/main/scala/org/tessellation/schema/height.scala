package org.tessellation.schema

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object height {

  @derive(encoder, decoder, order, show)
  @newtype
  case class Height(value: Long)

}
