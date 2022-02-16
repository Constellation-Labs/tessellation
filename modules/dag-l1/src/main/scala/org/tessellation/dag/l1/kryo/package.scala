package org.tessellation.dag.l1

import org.tessellation.dag.l1.domain.block.Tips
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._

package object kryo {

  val stateChannelKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[PeerBlockConsensusInput] -> 1001,
    classOf[Proposal] -> 1002,
    classOf[BlockProposal] -> 1003,
    classOf[CancelledBlockCreationRound] -> 1004,
    classOf[Tips] -> 1005,
    classOf[CancellationReason] -> 1006,
    ReceivedProposalForNonExistentOwnRound.getClass -> 1007,
    MissingRoundPeers.getClass -> 1008,
    CreatedInvalidBlock.getClass -> 1009,
    PeerCancelled.getClass -> 1010
  )
}
