package org.tessellation.schema

import cats.syntax.show._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object node {

  @derive(encoder, decoder, eqv, show)
  sealed trait NodeState

  object NodeState {
    case object Initial extends NodeState
    case object ReadyToJoin extends NodeState

    case object LoadingGenesis extends NodeState
    case object GenesisReady extends NodeState

    case object StartingSession extends NodeState
    case object SessionStarted extends NodeState

    case object Ready extends NodeState
    case object Offline extends NodeState

    case object Unknown extends NodeState

    val all: Set[NodeState] =
      Set(Initial, ReadyToJoin, LoadingGenesis, GenesisReady, StartingSession, SessionStarted, Ready, Offline, Unknown)

    val toBroadcast: Set[NodeState] =
      Set(Ready, Offline)
  }

  @derive(eqv, show)
  sealed trait NodeStateTransition

  object NodeStateTransition {
    case object Success extends NodeStateTransition
    case object Failure extends NodeStateTransition
  }

  case class InvalidNodeStateTransition(current: NodeState, from: Set[NodeState], to: NodeState)
      extends Throwable(s"Invalid node state transition from ${from.show} to ${to.show} but current is ${current.show}")

}
