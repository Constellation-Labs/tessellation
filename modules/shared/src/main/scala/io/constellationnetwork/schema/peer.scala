package io.constellationnetwork.schema

import java.security.PublicKey
import java.util.UUID

import cats.Show
import cats.effect.Async
import cats.kernel.Order
import cats.syntax.contravariant._
import cats.syntax.eq._
import cats.syntax.functor._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops._

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia._
import derevo.derive
import derevo.scalacheck.arbitrary
import fs2.data.csv.CellDecoder
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import monocle.macros.GenLens
import monocle.{Iso, Lens}

object peer {

  @derive(eqv, show, decoder, encoder)
  case class P2PContext(ip: Host, port: Port, id: PeerId)

  @derive(arbitrary, eqv, order, decoder, encoder, keyEncoder, keyDecoder)
  @newtype
  case class PeerId(value: Hex)

  object PeerId {

    implicit val show: Show[PeerId] = Show[Id].contramap(_.toId)

    val _Id: Iso[PeerId, Id] =
      Iso[PeerId, Id](peerId => Id(peerId.coerce))(id => PeerId(id.hex))

    implicit def ordering: Ordering[PeerId] = Order[PeerId].toOrdering

    implicit val cellDecoder: CellDecoder[PeerId] = CellDecoder.stringDecoder
      .map(Hex(_))
      .map(PeerId(_))

    val fromId: Id => PeerId = _Id.reverseGet

    def fromPublic(publicKey: PublicKey): PeerId =
      fromId(publicKey.toId)

    val shortShow: Show[PeerId] = Show.show[PeerId](p => s"PeerId(${p.value.value.take(5)})")
  }

  implicit class PeerIdOps(peerId: PeerId) {
    def toId: Id = PeerId._Id.get(peerId)

    def toAddress[F[_]: Async](implicit sc: SecurityProvider[F]): F[Address] =
      peerId.value.toPublicKey
        .map(_.toAddress)
  }

  @derive(eqv, show)
  sealed trait PeerResponsiveness

  case object Responsive extends PeerResponsiveness
  case object Unresponsive extends PeerResponsiveness

  object PeerResponsiveness {
    implicit val encode: Encoder[PeerResponsiveness] = Encoder.encodeString.contramap {
      case Responsive   => "Responsive"
      case Unresponsive => "Unresponsive"
    }

    implicit val decode: Decoder[PeerResponsiveness] = Decoder.decodeString.map {
      case "Responsive" => Responsive
      case _            => Unresponsive
    }

    val _Bool: Iso[PeerResponsiveness, Boolean] =
      Iso[PeerResponsiveness, Boolean] {
        case Responsive   => true
        case Unresponsive => false
      }(if (_) Responsive else Unresponsive)
  }

  @derive(eqv, encoder, decoder, show)
  case class Peer(
    id: PeerId,
    ip: Host,
    publicPort: Port,
    p2pPort: Port,
    clusterSession: ClusterSessionToken,
    session: SessionToken,
    state: NodeState,
    responsiveness: PeerResponsiveness,
    jar: Hash
  )

  object Peer {
    implicit def toP2PContext(peer: Peer): P2PContext =
      P2PContext(peer.ip, peer.p2pPort, peer.id)

    val _State: Lens[Peer, NodeState] = GenLens[Peer](_.state)
  }

  implicit class PeerOps(value: Peer) {
    def isResponsive: Boolean = value.responsiveness === Responsive
  }

  /** Per-peer committee membership status. JSON wire form is the lowercase label ("active"/"chronic"/"probation"); preserved verbatim
    * across the type-tightening from `String` so dashboards and downstream consumers continue working unchanged.
    *
    * Semantics:
    *   - `Active` — passing chronic threshold AND not on probation
    *   - `Chronic` — peerQuality ratio below `minParticipationRatio` after `minObservationHistoryFloor` observations
    *   - `Probation` — entry in `readmissionCountdown`, awaiting an AdmissionCertificate
    */
  sealed abstract class PeerCommitteeStatus(val label: String)
  object PeerCommitteeStatus {
    case object Active extends PeerCommitteeStatus("active")
    case object Chronic extends PeerCommitteeStatus("chronic")
    case object Probation extends PeerCommitteeStatus("probation")

    val all: Set[PeerCommitteeStatus] = Set(Active, Chronic, Probation)

    implicit val encoder: Encoder[PeerCommitteeStatus] = Encoder[String].contramap(_.label)
    implicit val decoder: Decoder[PeerCommitteeStatus] = Decoder[String].emap { s =>
      all.find(_.label == s).toRight(s"unknown peerCommitteeStatus: $s")
    }
    implicit val show: Show[PeerCommitteeStatus] = Show.show(_.label)
  }

  @derive(encoder, decoder, show)
  case class PeerCommitteeView(
    status: PeerCommitteeStatus,
    completed: Int,
    participated: Int,
    ratio: Double,
    probationRoundsRemaining: Option[Int]
  )

  @derive(encoder, decoder, show)
  case class PeerInfo(
    id: PeerId,
    ip: Host,
    publicPort: Port,
    p2pPort: Port,
    clusterSession: String,
    session: String,
    state: NodeState,
    jar: Hash,
    // Optional per-peer committee view. Only populated by the dag-l0 `/cluster/info` endpoint
    // (other modules omit it, leaving `None`). Backwards-compat additive field — circe leniently
    // ignores unknown fields, so old clients with old PeerInfo decoders still parse this response.
    peerCommittee: Option[PeerCommitteeView] = None
  )

  object PeerInfo {
    def fromPeer(peer: Peer): PeerInfo =
      PeerInfo(
        peer.id,
        peer.ip,
        peer.publicPort,
        peer.p2pPort,
        peer.clusterSession.toString,
        peer.session.value.toString,
        peer.state,
        peer.jar,
        peerCommittee = None
      )
  }

  @derive(eqv, encoder, decoder, order, ordering, show)
  case class L0Peer(id: PeerId, ip: Host, port: Port)

  object L0Peer {
    implicit def toP2PContext(l0Peer: L0Peer): P2PContext =
      P2PContext(l0Peer.ip, l0Peer.port, l0Peer.id)

    def fromPeerInfo(p: PeerInfo): L0Peer =
      L0Peer(p.id, p.ip, p.publicPort)

    def fromPeer(p: Peer): L0Peer =
      L0Peer(p.id, p.ip, p.publicPort)
  }

  @derive(eqv, show)
  case class FullPeer(
    data: Peer
  )

  @derive(eqv, decoder, encoder, show)
  case class RegistrationRequest(
    id: PeerId,
    ip: Host,
    publicPort: Port,
    p2pPort: Port,
    session: SessionToken,
    clusterSession: ClusterSessionToken,
    clusterId: ClusterId,
    state: NodeState,
    seedlist: Hash,
    version: Hash,
    metagraphVersion: Hash,
    jar: Hash,
    environment: AppEnvironment,
    allowanceList: Hash,
    metagraphId: Option[Address],
    consensusConfigHash: Option[Hash] = None
  )

  @derive(eqv, decoder, encoder, show)
  case class SignRequest(value: UUID)

  object SignRequest

  @derive(eqv, decoder, encoder, show)
  case class JoinRequest(
    registrationRequest: RegistrationRequest
  )

}
