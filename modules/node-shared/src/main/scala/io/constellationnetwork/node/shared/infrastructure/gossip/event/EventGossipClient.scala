package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.Async

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.security.SecurityProvider

import io.circe.{Decoder, Encoder}
import org.http4s.Method._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client

/** Client for event gossip P2P communication.
  *
  * Provides methods to:
  *   - Push events to peers
  *   - Get IHAVE announcements from peers
  *   - Request events via IWANT
  *
  * @tparam Event
  *   The event type for push/pull operations
  */
trait EventGossipClient[F[_], Event] {

  /** Push an event to a peer.
    *
    * @param push
    *   The event push message
    * @return
    *   Whether the push was successful
    */
  def pushEvent(push: EventPush[Event]): PeerResponse[F, Boolean]

  /** Get the IHAVE announcement from a peer.
    *
    * @return
    *   Set of event hashes the peer has
    */
  def getIHave: PeerResponse[F, IHave]

  /** Request events by hash from a peer.
    *
    * @param iwant
    *   The event hashes to request
    * @return
    *   Batch of events matching the requested hashes
    */
  def requestEvents(iwant: IWantRequest): PeerResponse[F, IWantResponse[Event]]
}

object EventGossipClient {

  def make[F[_]: Async: SecurityProvider, Event: Encoder: Decoder](
    client: Client[F],
    session: Session[F]
  ): EventGossipClient[F, Event] =
    new EventGossipClient[F, Event] {

      override def pushEvent(push: EventPush[Event]): PeerResponse[F, Boolean] =
        PeerResponse[F, F, Boolean]("events/push", POST)(client, session) { (req, c) =>
          c.successful(req.withEntity(push))
        }

      override def getIHave: PeerResponse[F, IHave] =
        PeerResponse[F, IHave]("events/ihave")(client, session)

      override def requestEvents(iwant: IWantRequest): PeerResponse[F, IWantResponse[Event]] =
        PeerResponse[F, F, IWantResponse[Event]]("events/iwant", POST)(client, session) { (req, c) =>
          c.expect[IWantResponse[Event]](req.withEntity(iwant))
        }
    }
}
