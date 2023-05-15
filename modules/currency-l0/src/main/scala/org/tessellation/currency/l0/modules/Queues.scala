package org.tessellation.currency.l0.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.tessellation.schema.Block
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.sdk.modules.SdkQueues
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object Queues {

  def make[F[_]: Concurrent](sdkQueues: SdkQueues[F]): F[Queues[F]] =
    for {
      l1OutputQueue <- Queue.unbounded[F, Signed[Block]]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sdkQueues.rumor
        val l1Output: Queue[F, Signed[Block]] = l1OutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val l1Output: Queue[F, Signed[Block]]
}
