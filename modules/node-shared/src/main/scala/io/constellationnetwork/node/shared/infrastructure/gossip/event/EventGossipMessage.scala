package io.constellationnetwork.node.shared.infrastructure.gossip.event

import java.nio.charset.StandardCharsets

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Encoder
import io.circe.syntax._

/** Messages for event gossip protocol (libp2p Gossipsub-inspired).
  */
sealed trait EventGossipMessage

object EventGossipBounds {
  val MaxIWantRequestHashes: Int = 128
  val MaxIWantResponseEvents: Int = 16

  /** Currency snapshot construction is capped at 512,000 bytes. Keep enough room for one maximal event plus its signed JSON envelope while
    * retaining a hard aggregate response bound. The separate 20 MiB GL0 event-cutter budget is an aggregate GL0 proposal limit, not the
    * Currency state-channel binary limit.
    */
  val MaxIWantResponseBytes: Int = 4 * 1024 * 1024

  def encodedResponseBytes[Event: Encoder](events: List[(Hash, Signed[Event])]): Int =
    IWantResponse(events).asJson.noSpaces.getBytes(StandardCharsets.UTF_8).length

  def isPullable[Event: Encoder](hash: Hash, event: Signed[Event]): Boolean =
    isPullableWithin(hash, event, MaxIWantResponseBytes)

  private[node] def isPullableWithin[Event: Encoder](hash: Hash, event: Signed[Event], maxBytes: Int): Boolean =
    encodedResponseBytes(List(hash -> event)) <= maxBytes
}

/** Chain tip metadata for fork recovery detection.
  *
  * Piggybacked on IHave messages so peers can detect when they are on a fork by comparing their local ordinal/hash against the majority of
  * their peers.
  */
@derive(encoder, decoder, eqv, show)
case class ChainTip(
  ordinal: SnapshotOrdinal,
  snapshotHash: Hash
)

/** Full event push (eager propagation to mesh peers).
  *
  * @tparam Event
  *   The event type being pushed
  */
@derive(encoder, decoder)
case class EventPush[Event](
  eventHash: Hash,
  event: Signed[Event]
) extends EventGossipMessage

/** Announce event hashes (lazy pull to non-mesh peers).
  *
  * Sent periodically to announce available events without sending full data. Optionally includes chain tip metadata for fork recovery
  * detection.
  */
@derive(encoder, decoder, eqv, show)
case class IHave(
  hashes: Set[Hash],
  chainTip: Option[ChainTip] = None
) extends EventGossipMessage

/** Request events by hash.
  *
  * Sent in response to IHave when missing events are detected.
  */
@derive(encoder, decoder, eqv, show)
case class IWantRequest(
  hashes: Set[Hash]
) extends EventGossipMessage

/** Response to IWant - batch of requested events.
  *
  * @tparam Event
  *   The event type being returned
  */
@derive(encoder, decoder, eqv, show)
case class IWantResponse[Event](
  events: List[(Hash, Signed[Event])]
) extends EventGossipMessage
