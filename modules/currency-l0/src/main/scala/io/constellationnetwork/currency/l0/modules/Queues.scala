package io.constellationnetwork.currency.l0.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import io.constellationnetwork.node.shared.modules.SharedQueues
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.security.Hashed

object Queues {

  def make[F[_]: Concurrent](sharedQueues: SharedQueues[F]): F[Queues[F]] =
    for {
      l1OutputQueue <- Queue.unbounded[F, CurrencySnapshotEvent]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sharedQueues.rumor
        val l1Output: Queue[F, CurrencySnapshotEvent] = l1OutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val l1Output: Queue[F, CurrencySnapshotEvent]
}
