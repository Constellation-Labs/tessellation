package org.tessellation.currency.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.DataUpdate
import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.domain.dataApplication.consensus.ConsensusInput
import org.tessellation.dag.l1.modules.{Queues => DAGL1Queues}
import org.tessellation.schema.Block
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object Queues {
  def make[F[_]: Concurrent, T <: Transaction, B <: Block[T]](dagL1Queues: DAGL1Queues[F, T, B]): F[Queues[F, T, B]] =
    for {

      dataApplicationPeerConsensusInputQueue <- Queue.unbounded[F, Signed[ConsensusInput.PeerConsensusInput]]
      dataApplicationBlockQueue <- Queue.unbounded[F, Signed[DataApplicationBlock]]
      dataUpdatesQueue <- Queue.unbounded[F, Signed[DataUpdate]]
    } yield
      new Queues[F, T, B] {
        val rumor = dagL1Queues.rumor
        val peerBlockConsensusInput = dagL1Queues.peerBlockConsensusInput
        val peerBlock = dagL1Queues.peerBlock
        val dataApplicationPeerConsensusInput = dataApplicationPeerConsensusInputQueue
        val dataApplicationBlock = dataApplicationBlockQueue
        val dataUpdates = dataUpdatesQueue
      }
}

sealed abstract class Queues[F[_], T <: Transaction, B <: Block[T]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput[T]]]
  val peerBlock: Queue[F, Signed[B]]
  val dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]]
  val dataApplicationBlock: Queue[F, Signed[DataApplicationBlock]]
  val dataUpdates: Queue[F, Signed[DataUpdate]]
}
