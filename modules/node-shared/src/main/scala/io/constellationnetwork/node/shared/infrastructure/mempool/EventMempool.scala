package io.constellationnetwork.node.shared.infrastructure.mempool

import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed._
import io.constellationnetwork.security.{Hashed, Hasher}

import io.circe.Encoder

/** Algebra for the event mempool.
  *
  * The mempool stores events by hash for later retrieval during consensus. Events are stored with their state-key metadata to enable
  * conflict detection and parallel processing.
  *
  * Note: Validation is NOT performed at mempool entry. L1 validates events before sending to L0, and AcceptanceManager validates during
  * consensus. The mempool is purely a hash-based storage layer.
  *
  * @tparam F
  *   Effect type
  * @tparam Event
  *   The event type
  * @tparam Key
  *   The state key type for conflict detection
  */
trait EventMempool[F[_], Event, Key] {

  /** Add a signed event envelope and report whether this invocation inserted that exact envelope.
    *
    * Trigger scheduling must distinguish a genuinely new envelope from an idempotent re-delivery. The legacy `add` result intentionally did
    * not expose that distinction: both cases returned the stored entry. Using a separate atomic result avoids the racy `contains(hash) >>
    * add(event)` pattern while preserving `add` for callers that only need storage semantics. Callers that need semantic deduplication must
    * key it by the unsigned event value; ECDSA makes independently-created signed envelope hashes non-deterministic.
    */
  def addWithStatus(
    event: Signed[Event]
  ): F[Either[MempoolRejectionReason, MempoolAddResult[Event, Key]]]

  /** Add an event to the mempool.
    *
    * @param event
    *   The signed event to add
    * @return
    *   Right(entry) if added, Left(reason) if rejected
    */
  def add(event: Signed[Event]): F[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]]

  /** Get an event by its hash.
    *
    * @param hash
    *   The event hash
    * @return
    *   Some(hashed) if found, None otherwise
    */
  def get(hash: Hash): F[Option[Hashed[Event]]]

  /** Get an event with its full metadata.
    *
    * @param hash
    *   The event hash
    * @return
    *   Some(entry) if found, None otherwise
    */
  def getWithMeta(hash: Hash): F[Option[MempoolEntry[Event, Key]]]

  /** Get multiple events by their hashes.
    *
    * @param hashes
    *   Set of event hashes to retrieve
    * @return
    *   Map of hash -> event for all found events
    */
  def getMultiple(hashes: Set[Hash]): F[Map[Hash, Hashed[Event]]]

  /** Remove events from the mempool.
    *
    * @param hashes
    *   Set of event hashes to remove
    */
  def remove(hashes: Set[Hash]): F[Unit]

  /** Check if an event exists in the mempool.
    *
    * @param hash
    *   The event hash
    * @return
    *   true if the event exists
    */
  def contains(hash: Hash): F[Boolean]

  /** Get a snapshot of the mempool for consensus proposal.
    *
    * Selects up to `limit` events in FIFO order (oldest first) based on insertion order, then wraps them in a `MempoolSnapshot`. Note that
    * `MempoolSnapshot.events` does not preserve this ordering — use the returned hash set for consensus and rely on the underlying event
    * data, not iteration order.
    *
    * @param limit
    *   Maximum number of events to include (default 10000)
    * @return
    *   Current mempool state bounded by limit
    */
  def snapshot(limit: Int = EventMempool.DefaultSnapshotLimit): F[MempoolSnapshot[Event, Key]]

  /** Get a snapshot of entries temporarily held out of proposal selection.
    */
  def suspendedSnapshot(limit: Int = EventMempool.DefaultSnapshotLimit): F[MempoolSnapshot[Event, Key]]

  /** Clear events that were included in a finalized snapshot.
    *
    * Called after consensus finalization to remove processed events.
    *
    * @param hashes
    *   Set of event hashes that were included
    */
  def clearIncluded(hashes: Set[Hash]): F[Unit]

  /** Temporarily hold events out of proposal selection while preserving their original receipt metadata.
    */
  def suspend(hashes: Set[Hash]): F[Unit]

  /** Return previously suspended events to normal proposal selection.
    */
  def reactivate(hashes: Set[Hash]): F[Unit]

  /** Get the current proposal-eligible size of the mempool.
    *
    * @return
    *   Number of active, non-suspended events in the mempool
    */
  def size: F[Int]

  /** Add multiple events to the mempool.
    *
    * @param events
    *   The signed events to add
    * @return
    *   List of results in the same order as input
    */
  def addBatch(events: List[Signed[Event]]): F[List[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]]]

  /** Get all event hashes currently in the mempool.
    *
    * Used for hash-based consensus bounds - each facilitator declares which events they have by hash.
    *
    * @return
    *   Set of all event hashes in the mempool
    */
  def getEventHashes: F[Set[Hash]]

  /** Clear all events from the mempool.
    *
    * Used during recovery downloads to discard stale events that would cause artifact mismatches with the healthy cluster.
    */
  def clear: F[Unit]
}

/** Result of an atomic mempool insertion attempt.
  *
  * `inserted=false` is a successful idempotent delivery of the exact signed envelope: `entry` is the already-stored value. It is not a
  * rejection and must not create fresh event-trigger intent or gossip fan-out.
  */
final case class MempoolAddResult[Event, Key](
  entry: MempoolEntry[Event, Key],
  inserted: Boolean
)

/** Configuration for the event mempool.
  *
  * TODO: Add a `trimOldEvents(maxAge: FiniteDuration)` method that the gossip daemon could call periodically. Currently events are only
  * trimmed via `clearIncluded` (which depends on consensus running). Events could accumulate if consensus stalls for an extended period.
  */
case class MempoolConfig(
  maxSize: Int
)

/** Internal state for the mempool implementation. Uses dual data structures for O(1) lookups and O(1) FIFO ordering.
  */
private[mempool] case class MempoolState[Event, Key](
  entries: Map[Hash, MempoolEntry[Event, Key]],
  insertionOrder: Vector[Hash],
  suspended: Set[Hash]
)

private[mempool] object MempoolState {
  def empty[Event, Key]: MempoolState[Event, Key] =
    MempoolState(Map.empty, Vector.empty, Set.empty)
}

object EventMempool {

  /** Protocol work bound reused by event gossip and Currency Facility construction. */
  val DefaultSnapshotLimit: Int = 10000

  /** Create a new event mempool.
    */
  def make[F[_]: Async: Hasher, Event: Encoder, Key](
    keyExtractor: StateKeyExtractor[F, Event, Key],
    config: MempoolConfig
  ): F[EventMempool[F, Event, Key]] =
    Ref.of[F, MempoolState[Event, Key]](MempoolState.empty).map { storage =>
      make(storage, keyExtractor, config)
    }

  /** In-memory implementation of the event mempool.
    *
    * Uses dual data structures:
    *   - Map[Hash, MempoolEntry] for O(1) hash lookups
    *   - Vector[Hash] for maintaining insertion order (FIFO)
    *
    * This avoids O(n log n) sorting on every snapshot() call.
    */
  def make[F[_]: Async: Hasher, Event: Encoder, Key](
    storage: Ref[F, MempoolState[Event, Key]],
    keyExtractor: StateKeyExtractor[F, Event, Key],
    config: MempoolConfig
  ): EventMempool[F, Event, Key] =
    new EventMempool[F, Event, Key] {

      def addWithStatus(
        event: Signed[Event]
      ): F[Either[MempoolRejectionReason, MempoolAddResult[Event, Key]]] =
        for {
          hashed <- event.toHashed
          stateKeys <- keyExtractor.extractKeys(hashed.signed.value)
          entry <- MempoolEntry(hashed, stateKeys)
          result <- storage.modify { state =>
            state.entries.get(hashed.hash) match {
              case Some(existing) =>
                // Duplicate: return existing entry without modifying state
                (state, MempoolAddResult(existing, inserted = false).asRight[MempoolRejectionReason])

              case None if state.entries.size >= config.maxSize =>
                // Mempool full: reject without modifying state
                (
                  state,
                  (MempoolRejectionReason.MempoolFull: MempoolRejectionReason)
                    .asLeft[MempoolAddResult[Event, Key]]
                )

              case None =>
                // New event: atomically insert
                val newState = state.copy(
                  entries = state.entries + (hashed.hash -> entry),
                  insertionOrder = state.insertionOrder :+ hashed.hash
                )
                (newState, MempoolAddResult(entry, inserted = true).asRight[MempoolRejectionReason])
            }
          }
        } yield result

      def add(event: Signed[Event]): F[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]] =
        addWithStatus(event).map(_.map(_.entry))

      def get(hash: Hash): F[Option[Hashed[Event]]] =
        storage.get.map(_.entries.get(hash).map(_.hashed))

      def getWithMeta(hash: Hash): F[Option[MempoolEntry[Event, Key]]] =
        storage.get.map(_.entries.get(hash))

      def getMultiple(hashes: Set[Hash]): F[Map[Hash, Hashed[Event]]] =
        storage.get.map { state =>
          hashes.flatMap(h => state.entries.get(h).map(e => h -> e.hashed)).toMap
        }

      def remove(hashes: Set[Hash]): F[Unit] =
        storage.update { state =>
          state.copy(
            entries = state.entries -- hashes,
            insertionOrder = state.insertionOrder.filterNot(hashes.contains),
            suspended = state.suspended -- hashes
          )
        }

      def contains(hash: Hash): F[Boolean] =
        storage.get.map(_.entries.contains(hash))

      def snapshot(limit: Int = EventMempool.DefaultSnapshotLimit): F[MempoolSnapshot[Event, Key]] =
        storage.get.map { state =>
          MempoolSnapshot(
            state.insertionOrder
              .filterNot(state.suspended.contains)
              .take(limit)
              .flatMap(h => state.entries.get(h).map(h -> _))
              .toMap
          )
        }

      def suspendedSnapshot(limit: Int = EventMempool.DefaultSnapshotLimit): F[MempoolSnapshot[Event, Key]] =
        storage.get.map { state =>
          MempoolSnapshot(
            state.insertionOrder
              .filter(state.suspended.contains)
              .take(limit)
              .flatMap(h => state.entries.get(h).map(h -> _))
              .toMap
          )
        }

      def clearIncluded(hashes: Set[Hash]): F[Unit] =
        remove(hashes)

      def suspend(hashes: Set[Hash]): F[Unit] =
        storage.update { state =>
          state.copy(suspended = state.suspended ++ hashes.filter(state.entries.contains))
        }

      def reactivate(hashes: Set[Hash]): F[Unit] =
        storage.update { state =>
          state.copy(suspended = state.suspended -- hashes)
        }

      def size: F[Int] =
        storage.get.map(state => state.entries.keySet.diff(state.suspended).size)

      def addBatch(events: List[Signed[Event]]): F[List[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]]] =
        events.traverse(add)

      def getEventHashes: F[Set[Hash]] =
        storage.get.map(state => state.entries.keySet -- state.suspended)

      def clear: F[Unit] =
        storage.set(MempoolState.empty[Event, Key])
    }
}
