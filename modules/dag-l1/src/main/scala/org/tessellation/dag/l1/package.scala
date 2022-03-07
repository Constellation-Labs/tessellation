package org.tessellation.dag

import org.tessellation.dag.domain.block.{L1Output, Tips}
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
    classOf[BlockProposal] -> 802,
    classOf[CancelledBlockCreationRound] -> 803,
    classOf[Tips] -> 804,
    classOf[CancellationReason] -> 805,
    ReceivedProposalForNonExistentOwnRound.getClass -> 806,
    MissingRoundPeers.getClass -> 807,
    CreatedInvalidBlock.getClass -> 808,
    PeerCancelled.getClass -> 809,
    classOf[L1Output] -> 810
  )
}
