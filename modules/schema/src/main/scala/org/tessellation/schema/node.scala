package org.tessellation.schema

import cats.syntax.show._

import scala.util.Try

import org.tessellation.schema.cluster.{ClusterSessionToken, SessionToken}
import org.tessellation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
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

    case object RollbackInProgress extends NodeState
    case object RollbackDone extends NodeState

    case object StartingSession extends NodeState
    case object SessionStarted extends NodeState

    case object WaitingForDownload extends NodeState
    case object DownloadInProgress extends NodeState

    case object Observing extends NodeState
    case object Ready extends NodeState
    case object Leaving extends NodeState
    case object Offline extends NodeState

    val all: Set[NodeState] = NodeState.values.toSet

    val toBroadcast: Set[NodeState] =
      Set(WaitingForDownload, DownloadInProgress, Observing, Ready, Leaving, Offline)

    def is(states: Set[NodeState])(peer: Peer) = states.contains(peer.state)

    def leaving: Set[NodeState] =
      Set(Leaving)

    def leaving(peers: Set[Peer]): Set[Peer] = peers.filter(peer => leaving.contains(peer.state))

    def absent: Set[NodeState] =
      Set(Offline)

    def absent(peers: Set[Peer]): Set[Peer] = peers.filter(peer => absent.contains(peer.state))

    def ready: Set[NodeState] =
      Set(Ready)

    def ready(peers: Set[Peer]): Set[Peer] = peers.filter(peer => ready.contains(peer.state))

    def observing: Set[NodeState] = Set(Observing)

    def observing(peers: Set[Peer]): Set[Peer] = peers.filter(peer => observing.contains(peer.state))

    val inCluster: Set[NodeState] = Set(Observing, Ready, WaitingForDownload, DownloadInProgress)

    def inCluster(state: NodeState): Boolean = inCluster.contains(state)

    val inConsensus: Set[NodeState] = Set(Observing, Ready)
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

  @derive(encoder)
  case class NodeInfo(
    state: NodeState,
    session: Option[SessionToken],
    clusterSession: Option[ClusterSessionToken],
    version: String,
    host: Host,
    publicPort: Port,
    p2pPort: Port,
    id: PeerId
  )

}
