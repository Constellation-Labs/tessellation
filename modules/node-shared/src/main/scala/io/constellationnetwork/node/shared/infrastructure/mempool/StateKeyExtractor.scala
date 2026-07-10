package io.constellationnetwork.node.shared.infrastructure.mempool

/** Extracts state keys from an event to determine what state the event modifies. Used for conflict detection and parallel processing during
  * consensus.
  *
  * Events with overlapping state keys conflict and must be processed sequentially. Events with disjoint state keys can be processed in
  * parallel.
  *
  * @tparam F
  *   Effect type
  * @tparam Event
  *   The event type (e.g., GlobalSnapshotEvent, CurrencySnapshotEvent)
  * @tparam Key
  *   The state key type (e.g., GlobalStateKey for dag-l0, CurrencyStateKey for currency-l0)
  */
trait StateKeyExtractor[F[_], Event, Key] {

  /** Extract the set of state keys that this event will modify.
    *
    * For a DAG transaction, this typically includes:
    *   - Source address balance key
    *   - Destination address balance key
    *   - Source address last transaction reference key
    *
    * @param event
    *   The event to analyze
    * @return
    *   Set of state keys this event touches
    */
  def extractKeys(event: Event): F[Set[Key]]
}

object StateKeyExtractor {

  def apply[F[_], Event, Key](implicit ev: StateKeyExtractor[F, Event, Key]): StateKeyExtractor[F, Event, Key] = ev
}
