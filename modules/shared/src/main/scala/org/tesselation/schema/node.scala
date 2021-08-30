package org.tesselation.schema

import derevo.cats.eqv
import derevo.derive

object node {

  @derive(eqv)
  sealed trait Status

  // TODO: FSM
  object Status {
    case object Initial extends Status
    case object Ready extends Status
    case object Offline extends Status
  }

}
