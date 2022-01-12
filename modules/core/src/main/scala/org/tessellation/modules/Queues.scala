package org.tessellation.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.schema.gossip.RumorBatch
import org.tessellation.sdk.modules.SdkQueues

object Queues {

  def make[F[_]: Concurrent](sdkQueues: SdkQueues[F]): F[Queues[F]] =
    for {
      stateChannelOutputQueue <- Queue.unbounded[F, StateChannelOutput]
    } yield
      new Queues[F] {
        val rumor = sdkQueues.rumor
        val stateChannelOutput = stateChannelOutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, RumorBatch]
  val stateChannelOutput: Queue[F, StateChannelOutput]
}
