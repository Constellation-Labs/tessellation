package org.tessellation.sdk.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.tessellation.schema.gossip.RumorBatch

object SdkQueues {

  def make[F[_]: Concurrent]: F[SdkQueues[F]] =
    for {
      rumorQueue <- Queue.unbounded[F, RumorBatch]
    } yield
      new SdkQueues[F] {
        val rumor = rumorQueue
      }
}

sealed abstract class SdkQueues[F[_]] private {
  val rumor: Queue[F, RumorBatch]
}
