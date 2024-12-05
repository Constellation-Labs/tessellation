package io.constellationnetwork.dag.l0.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.modules.SharedQueues
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

object Queues {

  def make[F[_]: Concurrent](sharedQueues: SharedQueues[F]): F[Queues[F]] =
    for {
      stateChannelOutputQueue <- Queue.unbounded[F, StateChannelOutput]
      l1OutputQueue <- Queue.unbounded[F, Signed[Block]]
      l1AllowSpendOutputQueue <- Queue.unbounded[F, Signed[AllowSpendBlock]]
      l1TokenLockOutputQueue <- Queue.unbounded[F, Signed[TokenLockBlock]]
    } yield
      new Queues[F] {
        val rumor = sharedQueues.rumor
        val stateChannelOutput = stateChannelOutputQueue
        val l1Output = l1OutputQueue
        val l1AllowSpendOutput = l1AllowSpendOutputQueue
        val l1TokenLockOutput = l1TokenLockOutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val stateChannelOutput: Queue[F, StateChannelOutput]
  val l1Output: Queue[F, Signed[Block]]
  val l1AllowSpendOutput: Queue[F, Signed[AllowSpendBlock]]
  val l1TokenLockOutput: Queue[F, Signed[TokenLockBlock]]
}
