package io.constellationnetwork.dag.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.currency.swap.{ConsensusInput => SwapConsensusInput}
import io.constellationnetwork.currency.tokenlock.{ConsensusInput => TokenLockConsensusInput}
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import io.constellationnetwork.node.shared.modules.SharedQueues
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

object Queues {

  def make[F[_]: Concurrent](sharedQueues: SharedQueues[F]): F[Queues[F]] =
    for {
      peerBlockConsensusInputQueue <- Queue.unbounded[F, Signed[PeerBlockConsensusInput]]
      peerBlockQueue <- Queue.unbounded[F, Signed[Block]]
      swapPeerConsensusInputQueue <- Queue.unbounded[F, Signed[SwapConsensusInput.PeerConsensusInput]]
      allowSpendBlocksQueue <- Queue.unbounded[F, Signed[AllowSpendBlock]]
      tokenLockPeerConsensusInputQueue <- Queue.unbounded[F, Signed[TokenLockConsensusInput.PeerConsensusInput]]
      tokenLocksQueue <- Queue.unbounded[F, Signed[TokenLockBlock]]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sharedQueues.rumor
        val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]] = peerBlockConsensusInputQueue
        val peerBlock: Queue[F, Signed[Block]] = peerBlockQueue
        val swapPeerConsensusInput = swapPeerConsensusInputQueue
        val allowSpendBlocks = allowSpendBlocksQueue
        val tokenLockConsensusInput = tokenLockPeerConsensusInputQueue
        val tokenLocksBlocks = tokenLocksQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[Block]]
  val swapPeerConsensusInput: Queue[F, Signed[SwapConsensusInput.PeerConsensusInput]]
  val allowSpendBlocks: Queue[F, Signed[AllowSpendBlock]]
  val tokenLockConsensusInput: Queue[F, Signed[TokenLockConsensusInput.PeerConsensusInput]]
  val tokenLocksBlocks: Queue[F, Signed[TokenLockBlock]]
}
