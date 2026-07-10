package io.constellationnetwork.node.shared.infrastructure.mempool

import java.time.Instant

import cats.effect.{Clock, Sync}
import cats.syntax.functor._

import io.constellationnetwork.security.Hashed

/** An entry in the event mempool.
  *
  * Contains the hashed event along with metadata needed for:
  *   - Conflict detection (via state keys)
  *   - TTL expiry (via receivedAt timestamp)
  *
  * @tparam Event
  *   The event type
  * @tparam Key
  *   The state key type for conflict detection
  * @param hashed
  *   The hashed event (includes signed event + hash + proofsHash)
  * @param stateKeys
  *   Set of state keys this event modifies (for conflict detection)
  * @param receivedAt
  *   Timestamp when the event was added to the mempool
  */
case class MempoolEntry[Event, Key](
  hashed: Hashed[Event],
  stateKeys: Set[Key],
  receivedAt: Instant
)

object MempoolEntry {

  /** Create a mempool entry
    */
  def apply[F[_]: Sync, Event, Key](
    hashed: Hashed[Event],
    stateKeys: Set[Key]
  ): F[MempoolEntry[Event, Key]] =
    Clock[F].realTimeInstant.map {
      new MempoolEntry(hashed, stateKeys, _)
    }

  /** Check if a mempool entry has expired based on TTL.
    *
    * @param entry
    *   The entry to check
    * @param maxAge
    *   Maximum age in milliseconds
    * @return
    *   true if the entry has expired
    */
  def isExpired[F[_]: Sync, Event, Key](entry: MempoolEntry[Event, Key], maxAge: Long): F[Boolean] =
    Clock[F].realTimeInstant.map { now =>
      val age = now.toEpochMilli - entry.receivedAt.toEpochMilli
      age > maxAge
    }
}
