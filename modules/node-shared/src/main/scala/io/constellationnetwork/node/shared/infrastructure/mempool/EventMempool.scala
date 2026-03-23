package io.constellationnetwork.node.shared.infrastructure.mempool

import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

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
    * Returns events in FIFO order (oldest first) up to the specified limit.
    *
    * @param limit
    *   Maximum number of events to include (default 10000)
    * @return
    *   Current mempool state bounded by limit
    */
  def snapshot(limit: Int = 10000): F[MempoolSnapshot[Event, Key]]

  /** Clear events that were included in a finalized snapshot.
    *
    * Called after consensus finalization to remove processed events.
    *
    * @param hashes
    *   Set of event hashes that were included
    */
  def clearIncluded(hashes: Set[Hash]): F[Unit]

  /** Get the current size of the mempool.
    *
    * @return
    *   Number of events in the mempool
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

/** Configuration for the event mempool.
  */
case class MempoolConfig(
  maxSize: Int,
  maxEventAge: FiniteDuration
)

/** Internal state for the mempool implementation. Uses dual data structures for O(1) lookups and O(1) FIFO ordering.
  */
private[mempool] case class MempoolState[Event, Key](
  entries: Map[Hash, MempoolEntry[Event, Key]],
  insertionOrder: Vector[Hash]
)

private[mempool] object MempoolState {
  def empty[Event, Key]: MempoolState[Event, Key] =
    MempoolState(Map.empty, Vector.empty)
}

object EventMempool {

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

      def add(event: Signed[Event]): F[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]] =
        for {
          currentSize <- size
          result <- (currentSize < config.maxSize)
            .pure[F]
            .ifM(
              ifTrue = doAdd(event),
              ifFalse = (MempoolRejectionReason.MempoolFull: MempoolRejectionReason).asLeft[MempoolEntry[Event, Key]].pure[F]
            )
        } yield result

      private def doAdd(event: Signed[Event]): F[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]] =
        for {
          hashed <- event.toHashed
          existing <- storage.get.map(_.entries.get(hashed.hash))

          result <- existing match {
            case Some(entry) =>
              entry.asRight[MempoolRejectionReason].pure[F]

            case None =>
              processEvent(hashed)
          }
        } yield result

      private def processEvent(
        hashedEvent: Hashed[Event]
      ): F[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]] =
        for {
          stateKeys <- keyExtractor.extractKeys(hashedEvent.signed.value)
          entry <- MempoolEntry(hashedEvent, stateKeys)
          _ <- storage.update { state =>
            state.copy(
              entries = state.entries + (hashedEvent.hash -> entry),
              insertionOrder = state.insertionOrder :+ hashedEvent.hash
            )
          }
        } yield entry.asRight[MempoolRejectionReason]

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
            insertionOrder = state.insertionOrder.filterNot(hashes.contains)
          )
        }

      def contains(hash: Hash): F[Boolean] =
        storage.get.map(_.entries.contains(hash))

      def snapshot(limit: Int = 10000): F[MempoolSnapshot[Event, Key]] =
        storage.get.map { state =>
          MempoolSnapshot(
            state.insertionOrder
              .take(limit)
              .flatMap(h => state.entries.get(h).map(h -> _))
              .toMap
          )
        }

      def clearIncluded(hashes: Set[Hash]): F[Unit] =
        remove(hashes)

      def size: F[Int] =
        storage.get.map(_.entries.size)

      def addBatch(events: List[Signed[Event]]): F[List[Either[MempoolRejectionReason, MempoolEntry[Event, Key]]]] =
        events.traverse(add)

      def getEventHashes: F[Set[Hash]] =
        storage.get.map(_.entries.keySet)

      def clear: F[Unit] =
        storage.set(MempoolState.empty[Event, Key])
    }
}
