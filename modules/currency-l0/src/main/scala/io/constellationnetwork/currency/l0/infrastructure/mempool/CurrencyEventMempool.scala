package io.constellationnetwork.currency.l0.infrastructure.mempool

import cats.effect.Async

import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.infrastructure.mempool._
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.security.Hasher

import io.circe.Encoder

object CurrencyEventMempool {

  def make[F[_]: Async: Hasher](
    config: MempoolConfig
  )(implicit eventEncoder: Encoder[CurrencySnapshotEvent]): F[EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey]] = {
    val keyExtractor = CurrencyStateKeyExtractor.make[F]
    EventMempool.make[F, CurrencySnapshotEvent, CurrencyStateKey](keyExtractor, config)
  }

  def defaultConfig: MempoolConfig = MempoolConfig(
    maxSize = 100000
  )
}
