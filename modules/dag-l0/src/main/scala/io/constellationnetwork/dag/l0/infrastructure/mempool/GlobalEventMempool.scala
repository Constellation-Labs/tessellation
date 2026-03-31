package io.constellationnetwork.dag.l0.infrastructure.mempool

import cats.effect.Async

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.node.shared.infrastructure.mempool._
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher

/** Factory for creating a GlobalSnapshotEvent mempool.
  *
  * Wires together the generic EventMempool with Global-specific implementations:
  *   - GlobalStateKeyExtractor: Extracts state keys for conflict detection
  *
  * Note: Validation is NOT performed at mempool entry. L1 validates events before sending to L0, and AcceptanceManager validates during
  * consensus.
  */
object GlobalEventMempool {

  /** Create a mempool for GlobalSnapshotEvent.
    *
    * @param config
    *   Configuration for the mempool
    * @return
    *   The mempool algebra
    */
  def make[F[_]: Async](
    config: MempoolConfig
  )(implicit hasher: Hasher[F]): F[EventMempool[F, GlobalSnapshotEvent, GlobalStateKey]] = {
    val keyExtractor = GlobalStateKeyExtractor.make[F]
    EventMempool.make[F, GlobalSnapshotEvent, GlobalStateKey](keyExtractor, config)
  }

  /** Default configuration for the global mempool.
    */
  def defaultConfig: MempoolConfig = MempoolConfig(
    maxSize = 100000
  )
}
