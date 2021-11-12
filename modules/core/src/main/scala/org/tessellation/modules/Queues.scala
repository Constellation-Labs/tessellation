package org.tessellation.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.tessellation.schema.gossip.RumorBatch

object Queues {

  def make[F[_]: Concurrent]: F[Queues[F]] =
    for {
      rumorQueue <- Queue.unbounded[F, RumorBatch]
    } yield
      new Queues[F] {
        val rumor = rumorQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, RumorBatch]
}
