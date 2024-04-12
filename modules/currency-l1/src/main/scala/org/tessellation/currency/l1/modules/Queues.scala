package org.tessellation.currency.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.dataApplication.{ConsensusInput, DataUpdate}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.dag.l1.modules.{Queues => DAGL1Queues}
import org.tessellation.node.shared.domain.queue.ViewableQueue
import org.tessellation.schema.Block
import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object Queues {
  def make[F[_]: Concurrent](dagL1Queues: DAGL1Queues[F]): F[Queues[F]] =
    for {
      dataApplicationPeerConsensusInputQueue <- Queue.unbounded[F, Signed[ConsensusInput.PeerConsensusInput]]
      dataApplicationBlockQueue <- Queue.unbounded[F, Signed[DataApplicationBlock]]
      dataUpdatesQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
    } yield
      new Queues[F] {
        val rumor = dagL1Queues.rumor
        val peerBlockConsensusInput = dagL1Queues.peerBlockConsensusInput
        val peerBlock = dagL1Queues.peerBlock
        val dataApplicationPeerConsensusInput = dataApplicationPeerConsensusInputQueue
        val dataApplicationBlock = dataApplicationBlockQueue
        val dataUpdates = dataUpdatesQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[Block]]
  val dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]]
  val dataApplicationBlock: Queue[F, Signed[DataApplicationBlock]]
  val dataUpdates: ViewableQueue[F, Signed[DataUpdate]]
}
