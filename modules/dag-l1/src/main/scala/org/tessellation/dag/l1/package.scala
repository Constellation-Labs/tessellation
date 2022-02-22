package org.tessellation.dag

import org.tessellation.dag.l1.domain.block.Tips
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._

package object l1 {

  val dagL1KryoRegistrar: Map[Class[_], Int] = Map(
    classOf[PeerBlockConsensusInput] -> 800,
    classOf[Proposal] -> 801,
    classOf[BlockProposal] -> 802,
    classOf[CancelledBlockCreationRound] -> 803,
    classOf[Tips] -> 804,
    classOf[CancellationReason] -> 805,
    ReceivedProposalForNonExistentOwnRound.getClass -> 806,
    MissingRoundPeers.getClass -> 807,
    CreatedInvalidBlock.getClass -> 808,
    PeerCancelled.getClass -> 809
  )
}
