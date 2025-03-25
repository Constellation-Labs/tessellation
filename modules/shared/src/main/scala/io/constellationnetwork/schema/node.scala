package io.constellationnetwork.schema

import cats.effect.Async
import cats.syntax.all._
import cats.{Eq, Order, Show}

import scala.util.Try

import io.constellationnetwork.ext.cats.data.OrderBasedOrdering
import io.constellationnetwork.ext.crypto.RefinedHasher
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster.{ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._
import eu.timepit.refined.api.{Refined, RefinedTypeOps}
import eu.timepit.refined.auto.{autoRefineV, _}
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.Interval.Closed
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe._
import io.estatico.newtype.macros.newtype

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

    case object WaitingForObserving extends NodeState
    case object Observing extends NodeState
    case object WaitingForReady extends NodeState
    case object Ready extends NodeState
    case object Leaving extends NodeState
    case object Offline extends NodeState

    val all: Set[NodeState] = NodeState.values.toSet

    val toBroadcast: Set[NodeState] =
      Set(WaitingForDownload, DownloadInProgress, WaitingForObserving, Observing, Ready, Leaving, Offline)

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

    val inCluster: Set[NodeState] = Set(WaitingForObserving, Observing, WaitingForReady, Ready, WaitingForDownload, DownloadInProgress)

    def inCluster(state: NodeState): Boolean = inCluster.contains(state)
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

  @derive(encoder)
  case class MetagraphNodeInfo(
    state: NodeState,
    session: Option[SessionToken],
    clusterSession: Option[ClusterSessionToken],
    host: Host,
    publicPort: Port,
    p2pPort: Port,
    id: PeerId,
    tessellationVersion: TessellationVersion,
    metagraphVersion: MetagraphVersion
  )

  type RewardFraction = Int Refined Closed[0, 100_000_000]
  object RewardFraction extends RefinedTypeOps.Numeric[RewardFraction, Int] {}

  implicit val rewardFractionEncoder: Encoder[RewardFraction] = Encoder.encodeInt.contramap(_.value)
  implicit val rewardFractionDecoder: Decoder[RewardFraction] = Decoder.decodeInt.emap(RewardFraction.from)
  implicit val rewardFractionEq: Eq[RewardFraction] = Eq.by(_.value)
  implicit val rewardFractionShow: Show[RewardFraction] = Show.show(_.value.toString)
  implicit val rewardFractionOrder: Order[RewardFraction] = Order.by(_.value)

  implicit object OrderingInstance extends OrderBasedOrdering[NonNegLong]

  @derive(decoder, encoder, show, order, ordering)
  @newtype
  case class UpdateNodeParametersOrdinal(value: NonNegLong) {
    def next: UpdateNodeParametersOrdinal = UpdateNodeParametersOrdinal(value |+| 1L)
  }

  @derive(decoder, encoder, order, show)
  case class UpdateNodeParametersReference(ordinal: UpdateNodeParametersOrdinal, hash: Hash)

  object UpdateNodeParametersReference {
    val empty = UpdateNodeParametersReference(UpdateNodeParametersOrdinal(0L), Hash.empty)

    def of[F[_]: Async: Hasher](signed: Signed[UpdateNodeParameters]): F[UpdateNodeParametersReference] =
      signed.value.hash.map(UpdateNodeParametersReference(signed.ordinal, _))
  }

  @derive(eqv, show, encoder, decoder)
  case class DelegatedStakeRewardParameters(rewardFraction: RewardFraction) {
    def reward: Double = rewardFraction.toDouble / DelegatedStakeRewardParameters.MaxRewardFraction
  }

  object DelegatedStakeRewardParameters {
    val MaxRewardFraction = RewardFraction(100_000_000)
  }

  @derive(eqv, show, encoder, decoder)
  case class NodeMetadataParameters(name: String, description: String)

  @derive(eqv, show, encoder, decoder, order, ordering)
  case class UpdateNodeParameters(
    source: Address,
    delegatedStakeRewardParameters: DelegatedStakeRewardParameters,
    nodeMetadataParameters: NodeMetadataParameters,
    parent: UpdateNodeParametersReference
  ) {
    def ordinal: UpdateNodeParametersOrdinal = parent.ordinal.next
  }

  @derive(eqv, show, encoder, decoder, order, ordering)
  case class NodeParamsInfo(
    latest: Signed[UpdateNodeParameters],
    lastRef: UpdateNodeParametersReference,
    acceptedOrdinal: SnapshotOrdinal
  )
}
