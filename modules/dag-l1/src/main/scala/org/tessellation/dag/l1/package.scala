package org.tessellation.dag

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._
import org.tessellation.schema.kryo.ProtocolKryoRegistrationId

import eu.timepit.refined.auto._

package object l1 {

  val dagL1KryoRegistrar: Map[Class[_], ProtocolKryoRegistrationId] = Map(
    classOf[PeerBlockConsensusInput] -> 800,
    classOf[Proposal] -> 801,
    classOf[BlockSignatureProposal] -> 802,
    classOf[CancelledBlockCreationRound] -> 803,
    classOf[CancellationReason] -> 804,
    ReceivedProposalForNonExistentOwnRound.getClass -> 805,
    MissingRoundPeers.getClass -> 806,
    CreatedInvalidBlock.getClass -> 807,
    CreatedBlockWithNoTransactions.getClass -> 808,
    PeerCancelled.getClass -> 809
  )
}
