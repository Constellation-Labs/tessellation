package io.constellationnetwork.dag.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import io.constellationnetwork.node.shared.modules.SharedQueues
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

object Queues {

  def make[F[_]: Concurrent](sharedQueues: SharedQueues[F]): F[Queues[F]] =
    for {
      peerBlockConsensusInputQueue <- Queue.unbounded[F, Signed[PeerBlockConsensusInput]]
      peerBlockQueue <- Queue.unbounded[F, Signed[Block]]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sharedQueues.rumor
        val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]] = peerBlockConsensusInputQueue
        val peerBlock: Queue[F, Signed[Block]] = peerBlockQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[Block]]
}
