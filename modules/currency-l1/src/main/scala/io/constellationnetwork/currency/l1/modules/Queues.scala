package io.constellationnetwork.currency.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.{ConsensusInput => DataConsensusInput}
import io.constellationnetwork.currency.swap.{ConsensusInput => SwapConsensusInput}
import io.constellationnetwork.currency.tokenlock.{ConsensusInput => TokenLockConsensusInput}
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import io.constellationnetwork.dag.l1.modules.{Queues => DAGL1Queues}
import io.constellationnetwork.node.shared.domain.queue.ViewableQueue
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

object Queues {
  def make[F[_]: Concurrent](dagL1Queues: DAGL1Queues[F]): F[Queues[F]] =
    for {
      dataApplicationPeerConsensusInputQueue <- Queue.unbounded[F, Signed[DataConsensusInput.PeerConsensusInput]]
      dataApplicationBlockQueue <- Queue.unbounded[F, Signed[DataApplicationBlock]]
      dataTransactionsQueue <- ViewableQueue.make[F, DataTransactions]
    } yield
      new Queues[F] {
        val rumor = dagL1Queues.rumor
        val peerBlockConsensusInput = dagL1Queues.peerBlockConsensusInput
        val peerBlock = dagL1Queues.peerBlock
        val dataApplicationPeerConsensusInput = dataApplicationPeerConsensusInputQueue
        val dataApplicationBlock = dataApplicationBlockQueue
        val dataTransactions = dataTransactionsQueue
        val swapPeerConsensusInput = dagL1Queues.swapPeerConsensusInput
        val allowSpendBlocks = dagL1Queues.allowSpendBlocks
        val tokenLockPeerConsensusInput = dagL1Queues.tokenLockConsensusInput
        val tokenLocksBlocks = dagL1Queues.tokenLocksBlocks
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[Block]]
  val dataApplicationPeerConsensusInput: Queue[F, Signed[DataConsensusInput.PeerConsensusInput]]
  val dataApplicationBlock: Queue[F, Signed[DataApplicationBlock]]
  val dataTransactions: ViewableQueue[F, DataTransactions]
  val swapPeerConsensusInput: Queue[F, Signed[SwapConsensusInput.PeerConsensusInput]]
  val allowSpendBlocks: Queue[F, Signed[AllowSpendBlock]]
  val tokenLockPeerConsensusInput: Queue[F, Signed[TokenLockConsensusInput.PeerConsensusInput]]
  val tokenLocksBlocks: Queue[F, Signed[TokenLockBlock]]
}
