package org.tessellation.currency.l0.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.tessellation.currency.l0.snapshot.CurrencySnapshotEvent
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.sdk.modules.SdkQueues
import org.tessellation.security.Hashed

object Queues {

  def make[F[_]: Concurrent](sdkQueues: SdkQueues[F]): F[Queues[F]] =
    for {
      l1OutputQueue <- Queue.unbounded[F, CurrencySnapshotEvent]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sdkQueues.rumor
        val l1Output: Queue[F, CurrencySnapshotEvent] = l1OutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val l1Output: Queue[F, CurrencySnapshotEvent]
}
