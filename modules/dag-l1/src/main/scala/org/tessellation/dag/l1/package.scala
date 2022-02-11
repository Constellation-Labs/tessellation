package org.tessellation.dag

import org.tessellation.dag.domain.block.Tips
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._

package object l1 {

  val dagL1KryoRegistrar: Map[Class[_], Int] = Map(
    classOf[PeerBlockConsensusInput] -> 700,
    classOf[Proposal] -> 701,
    classOf[BlockProposal] -> 702,
    classOf[CancelledBlockCreationRound] -> 703,
    classOf[Tips] -> 704,
    classOf[CancellationReason] -> 705,
    ReceivedProposalForNonExistentOwnRound.getClass -> 706,
    MissingRoundPeers.getClass -> 707,
    CreatedInvalidBlock.getClass -> 708,
    PeerCancelled.getClass -> 709
  )
}
