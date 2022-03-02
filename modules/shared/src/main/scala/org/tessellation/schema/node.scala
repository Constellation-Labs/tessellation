package org.tessellation.schema

import cats.syntax.show._

import scala.util.Try

import org.tessellation.schema.peer.Peer

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum._
import io.circe._

object node {

  @derive(eqv, show)
  sealed trait NodeState extends EnumEntry

  object NodeState extends Enum[NodeState] with NodeStateCodecs {
    val values = findValues

    case object Initial extends NodeState
    case object ReadyToJoin extends NodeState

    case object LoadingGenesis extends NodeState
    case object GenesisReady extends NodeState

    case object StartingSession extends NodeState
    case object SessionStarted extends NodeState

    case object WaitingForDownload extends NodeState
    case object DownloadInProgress extends NodeState

    case object Ready extends NodeState
    case object Leaving extends NodeState
    case object Offline extends NodeState

    val all: Set[NodeState] = NodeState.values.toSet

    val toBroadcast: Set[NodeState] =
      Set(WaitingForDownload, DownloadInProgress, Ready, Leaving, Offline)

    def absent: Set[NodeState] =
      Set(Leaving, Offline)

    def absent(peers: Set[Peer]): Set[Peer] = peers.filter(peer => absent.contains(peer.state))

    def ready: Set[NodeState] =
      Set(Ready)

    def ready(peers: Set[Peer]): Set[Peer] = peers.filter(peer => ready.contains(peer.state))
  }

  trait NodeStateCodecs {
    implicit val encode: Encoder[NodeState] = Encoder.encodeString.contramap[NodeState](_.entryName)
    implicit val decode: Decoder[NodeState] = Decoder.decodeString.emapTry(s => Try(NodeState.withName(s)))
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
