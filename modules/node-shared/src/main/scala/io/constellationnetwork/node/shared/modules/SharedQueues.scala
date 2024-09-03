package io.constellationnetwork.node.shared.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.security.Hashed

object SharedQueues {

  def make[F[_]: Concurrent]: F[SharedQueues[F]] =
    for {
      rumorQueue <- Queue.unbounded[F, Hashed[RumorRaw]]
    } yield
      new SharedQueues[F] {
        val rumor = rumorQueue
      }
}

sealed abstract class SharedQueues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
}
