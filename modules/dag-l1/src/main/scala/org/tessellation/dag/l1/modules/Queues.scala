package org.tessellation.dag.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.schema.Block
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.sdk.modules.SdkQueues
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object Queues {

  def make[F[_]: Concurrent, B <: Block](sdkQueues: SdkQueues[F]): F[Queues[F, B]] =
    for {
      peerBlockConsensusInputQueue <- Queue.unbounded[F, Signed[PeerBlockConsensusInput]]
      peerBlockQueue <- Queue.unbounded[F, Signed[B]]
    } yield
      new Queues[F, B] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sdkQueues.rumor
        val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]] = peerBlockConsensusInputQueue
        val peerBlock: Queue[F, Signed[B]] = peerBlockQueue
      }
}

sealed abstract class Queues[F[_], B <: Block] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[B]]
}
