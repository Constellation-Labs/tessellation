package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.data.NonEmptySet._
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class ConsensusState[Key, Artifact](
  key: Key,
  facilitators: List[PeerId],
  lastKeyAndArtifact: (Key, Signed[Artifact]),
  status: ConsensusStatus[Artifact],
  statusUpdatedAt: FiniteDuration
)

@derive(eqv)
sealed trait ConsensusStatus[Artifact]

object ConsensusStatus {
  implicit def showInstance[A]: Show[ConsensusStatus[A]] = {
    case Facilitated(proposalTrigger) => s"Facilitated{proposalTrigger=${proposalTrigger.show}}"
    case ProposalMade(proposalHash, majorityTrigger, _) =>
      s"ProposalMade{proposalHash=$proposalHash, majorityTrigger=$majorityTrigger, proposalArtifact=***}"
    case MajoritySigned(majorityHash, majorityTrigger) =>
      s"MajoritySigned{majorityHash=$majorityHash, majorityTrigger=$majorityTrigger}"
    case Finished(_, majorityTrigger) =>
      s"Finished{signedMajorityArtifact=***, majorityTrigger=$majorityTrigger}"
  }
}

final case class Facilitated[A](proposalTrigger: Option[ConsensusTrigger]) extends ConsensusStatus[A]
final case class ProposalMade[A](proposalHash: Hash, majorityTrigger: ConsensusTrigger, proposalArtifact: A)
    extends ConsensusStatus[A]
final case class MajoritySigned[A](majorityHash: Hash, majorityTrigger: ConsensusTrigger) extends ConsensusStatus[A]
final case class Finished[A](signedMajorityArtifact: Signed[A], majorityTrigger: ConsensusTrigger)
    extends ConsensusStatus[A]
