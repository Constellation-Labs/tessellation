package org.tessellation.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.schema.gossip.RumorBatch

object Queues {

  def make[F[_]: Concurrent]: F[Queues[F]] =
    for {
      rumorQueue <- Queue.unbounded[F, RumorBatch]
      stateChannelOutputQueue <- Queue.unbounded[F, StateChannelOutput]
    } yield
      new Queues[F] {
        val rumor = rumorQueue
        val stateChannelOutput = stateChannelOutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, RumorBatch]
  val stateChannelOutput: Queue[F, StateChannelOutput]
}
