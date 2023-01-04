package org.tessellation.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.sdk.modules.SdkQueues
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

object Queues {

  def make[F[_]: Concurrent](sdkQueues: SdkQueues[F]): F[Queues[F]] =
    for {
      stateChannelOutputQueue <- Queue.unbounded[F, StateChannelOutput]
      l1OutputQueue <- Queue.unbounded[F, Signed[DAGBlock]]
    } yield
      new Queues[F] {
        val rumor = sdkQueues.rumor
        val stateChannelOutput = stateChannelOutputQueue
        val l1Output = l1OutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val stateChannelOutput: Queue[F, StateChannelOutput]
  val l1Output: Queue[F, Signed[DAGBlock]]
}
