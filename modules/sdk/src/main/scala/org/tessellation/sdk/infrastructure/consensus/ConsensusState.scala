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
  lastKey: Key,
  facilitators: List[PeerId],
  status: ConsensusStatus[Artifact],
  statusUpdatedAt: FiniteDuration
)

@derive(eqv)
sealed trait ConsensusStatus[Artifact]

final case class CollectingFacilities[A](maybeFacilityInfo: Option[FacilityInfo[A]]) extends ConsensusStatus[A]
final case class CollectingProposals[A](majorityTrigger: ConsensusTrigger, maybeProposalInfo: Option[ProposalInfo[A]])
    extends ConsensusStatus[A]
final case class CollectingSignatures[A](majorityArtifactHash: Hash, majorityTrigger: ConsensusTrigger) extends ConsensusStatus[A]
final case class Finished[A](signedMajorityArtifact: Signed[A], majorityTrigger: ConsensusTrigger) extends ConsensusStatus[A]

object ConsensusStatus {
  implicit def showInstance[A]: Show[ConsensusStatus[A]] = {
    case CollectingFacilities(maybeFacilityInfo) =>
      s"CollectingFacilities{maybeFacilityInfo=${maybeFacilityInfo.show}}"
    case CollectingProposals(majorityTrigger, maybeProposalInfo) =>
      s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, maybeProposalInfo=${maybeProposalInfo.show}}"
    case CollectingSignatures(majorityArtifactHash, majorityTrigger) =>
      s"CollectingSignatures{majorityArtifactHash=${majorityArtifactHash.show}, ${majorityTrigger.show}}"
    case Finished(_, majorityTrigger) =>
      s"Finished{signedMajorityArtifact=***, majorityTrigger=${majorityTrigger.show}}"
  }
}

@derive(eqv)
case class FacilityInfo[A](lastSignedArtifact: Signed[A], maybeTrigger: Option[ConsensusTrigger])
object FacilityInfo {
  implicit def showInstance[A]: Show[FacilityInfo[A]] = f => s"FacilityInfo{lastSignedArtifact=***, maybeTrigger=${f.maybeTrigger.show}}"
}

@derive(eqv)
case class ProposalInfo[A](proposalArtifact: A, proposalArtifactHash: Hash)
object ProposalInfo {
  implicit def showInstance[A]: Show[ProposalInfo[A]] = pi =>
    s"ProposalInfo{proposalArtifact=***, proposalArtifactHash=${pi.proposalArtifactHash.show}}"
}
