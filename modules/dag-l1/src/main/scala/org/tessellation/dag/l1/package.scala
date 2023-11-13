package org.tessellation.dag

import org.tessellation.currency.dataApplication.{ConsensusInput, DataCancellationReason}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._
import org.tessellation.ext.kryo._

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval

package object l1 {

  type DagL1KryoRegistrationIdRange = Interval.Closed[800, 899]

  type DagL1KryoRegistrationId = KryoRegistrationId[DagL1KryoRegistrationIdRange]

  val dagL1KryoRegistrar: Map[Class[_], DagL1KryoRegistrationId] = Map(
    classOf[PeerBlockConsensusInput] -> 800,
    classOf[Proposal] -> 801,
    classOf[BlockSignatureProposal] -> 802,
    classOf[CancelledBlockCreationRound] -> 803,
    classOf[CancellationReason] -> 804,
    ReceivedProposalForNonExistentOwnRound.getClass -> 805,
    MissingRoundPeers.getClass -> 806,
    CreatedInvalidBlock.getClass -> 807,
    CreatedBlockWithNoTransactions.getClass -> 808,
    PeerCancelled.getClass -> 809,
    classOf[ConsensusInput.Proposal] -> 810,
    classOf[ConsensusInput.SignatureProposal] -> 811,
    classOf[ConsensusInput.CancelledCreationRound] -> 812,
    DataCancellationReason.getClass -> 813
  )
}
