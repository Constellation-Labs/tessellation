package io.constellationnetwork.currency.l0.infrastructure.mempool

import cats.effect.Async

import scala.concurrent.duration._

import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.infrastructure.mempool._
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.security.Hasher

import io.circe.Encoder

/** Factory for creating a CurrencySnapshotEvent mempool.
  *
  * Wires together the generic EventMempool with Currency-specific implementations:
  *   - CurrencyStateKeyExtractor: Extracts state keys for events
  *
  * Note: Validation is NOT performed at mempool entry. L1 validates events before sending to L0, and AcceptanceManager validates during
  * consensus.
  */
object CurrencyEventMempool {

  /** Create a mempool for CurrencySnapshotEvent.
    *
    * @param config
    *   Configuration for the mempool
    * @return
    *   The mempool algebra
    */
  def make[F[_]: Async: Hasher](
    config: MempoolConfig
  )(implicit eventEncoder: Encoder[CurrencySnapshotEvent]): F[EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey]] = {
    val keyExtractor = CurrencyStateKeyExtractor.make[F]
    EventMempool.make[F, CurrencySnapshotEvent, CurrencyStateKey](keyExtractor, config)
  }

  /** Default configuration for the currency mempool.
    */
  def defaultConfig: MempoolConfig = MempoolConfig(
    maxSize = 100000,
    maxEventAge = 5.minutes
  )
}
