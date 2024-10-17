package io.constellationnetwork.dag.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.currency.swap.{ConsensusInput => SwapConsensusInput}
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import io.constellationnetwork.node.shared.domain.queue.ViewableQueue
import io.constellationnetwork.node.shared.modules.SharedQueues
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.swap.SwapTransaction
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

object Queues {

  def make[F[_]: Concurrent](sharedQueues: SharedQueues[F]): F[Queues[F]] =
    for {
      peerBlockConsensusInputQueue <- Queue.unbounded[F, Signed[PeerBlockConsensusInput]]
      peerBlockQueue <- Queue.unbounded[F, Signed[Block]]
      swapPeerConsensusInputQueue <- Queue.unbounded[F, Signed[SwapConsensusInput.PeerConsensusInput]]
      swapTransactionsQueue <- ViewableQueue.make[F, Signed[SwapTransaction]]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sharedQueues.rumor
        val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]] = peerBlockConsensusInputQueue
        val peerBlock: Queue[F, Signed[Block]] = peerBlockQueue
        val swapPeerConsensusInput = swapPeerConsensusInputQueue
        val swapTransactions = swapTransactionsQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[Block]]
  val swapPeerConsensusInput: Queue[F, Signed[SwapConsensusInput.PeerConsensusInput]]
  val swapTransactions: ViewableQueue[F, Signed[SwapTransaction]]
}
