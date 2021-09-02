package org.tesselation.schema

import derevo.cats.{eqv, show}
import derevo.derive

object node {

  @derive(eqv, show)
  sealed trait NodeState

  // TODO: FSM
  object NodeState {
    case object Initial extends NodeState
    case object Ready extends NodeState
    case object Offline extends NodeState
  }

}
