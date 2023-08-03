package org.tessellation.currency.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.dataApplication.{ConsensusInput, DataUpdate}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.modules.{Queues => DAGL1Queues}
import org.tessellation.schema.Block
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object Queues {
  def make[F[_]: Concurrent, B <: Block](dagL1Queues: DAGL1Queues[F, B]): F[Queues[F, B]] =
    for {

      dataApplicationPeerConsensusInputQueue <- Queue.unbounded[F, Signed[ConsensusInput.PeerConsensusInput]]
      dataApplicationBlockQueue <- Queue.unbounded[F, Signed[DataApplicationBlock]]
      dataUpdatesQueue <- Queue.unbounded[F, Signed[DataUpdate]]
    } yield
      new Queues[F, B] {
        val rumor = dagL1Queues.rumor
        val peerBlockConsensusInput = dagL1Queues.peerBlockConsensusInput
        val peerBlock = dagL1Queues.peerBlock
        val dataApplicationPeerConsensusInput = dataApplicationPeerConsensusInputQueue
        val dataApplicationBlock = dataApplicationBlockQueue
        val dataUpdates = dataUpdatesQueue
      }
}

sealed abstract class Queues[F[_], B <: Block] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[B]]
  val dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]]
  val dataApplicationBlock: Queue[F, Signed[DataApplicationBlock]]
  val dataUpdates: Queue[F, Signed[DataUpdate]]
}
