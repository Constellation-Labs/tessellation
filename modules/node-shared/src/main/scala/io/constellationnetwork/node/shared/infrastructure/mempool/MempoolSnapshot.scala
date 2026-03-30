package io.constellationnetwork.node.shared.infrastructure.mempool

import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

/** A snapshot of the mempool state.
  *
  * Used during consensus proposal to select events to include. Events are stored in an unordered Map; iteration order is not guaranteed to
  * match insertion order. Use `hashes` for set-based operations and avoid relying on `events` ordering.
  *
  * @tparam Event
  *   The event type
  * @tparam Key
  *   The state key type for conflict detection
  * @param entries
  *   All entries currently in the mempool
  */
case class MempoolSnapshot[Event, Key](
  entries: Map[Hash, MempoolEntry[Event, Key]]
) {

  /** Get all event hashes */
  def hashes: Set[Hash] = entries.keySet

  /** Get all events. Note: order is not guaranteed to match insertion order. */
  def events: List[Hashed[Event]] = entries.values.map(_.hashed).toList

  /** Number of events */
  def size: Int = entries.size
}

object MempoolSnapshot {

  def empty[Event, Key]: MempoolSnapshot[Event, Key] = MempoolSnapshot(Map.empty)
}
