package io.constellationnetwork.node.shared.infrastructure.gossip.event

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Messages for event gossip protocol (libp2p Gossipsub-inspired).
  */
sealed trait EventGossipMessage

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
