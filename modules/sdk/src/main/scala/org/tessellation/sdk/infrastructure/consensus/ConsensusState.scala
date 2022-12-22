package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.syntax.eq._
import cats.syntax.option._
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.infrastructure.consensus.declaration.kind._
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv)
case class ConsensusState[Key, Artifact](
  key: Key,
  lastKey: Key,
  facilitators: List[PeerId],
  status: ConsensusStatus[Artifact],
  createdAt: FiniteDuration,
  removedFacilitators: Set[PeerId] = Set.empty,
  lockStatus: LockStatus = Open,
  spreadAckKinds: Set[PeerDeclarationKind] = Set.empty
)

object ConsensusState {
  implicit def showInstance[K: Show, A]: Show[ConsensusState[K, A]] = {
    case ConsensusState(key, _, facilitators, status, _, removedFacilitators, locked, ackKinds) =>
      s"ConsensusState{key=${key.show}, lockStatus=${locked.show}, facilitatorCount=${facilitators.size.show}, removedFacilitatorCount=${removedFacilitators.size.show}, ackKinds=${ackKinds.show}, status=${status.show}}"
  }

  implicit class ConsensusStateOps[K, A](value: ConsensusState[K, A]) {
    private val kindRelation: (Option[PeerDeclarationKind], Set[PeerDeclarationKind]) = value.status match {
      case _: CollectingFacilities[A] => (Facility.some, Set.empty)
      case _: CollectingProposals[A]  => (Proposal.some, Set(Facility))
      case _: CollectingSignatures[A] => (MajoritySignature.some, Set(Facility, Proposal))
      case _: Finished[A]             => (none, Set(Facility, Proposal, MajoritySignature))
    }

    def collectedKinds: Set[PeerDeclarationKind] = kindRelation._2
    def maybeCollectingKind: Option[PeerDeclarationKind] = kindRelation._1
    def locked: Boolean = value.lockStatus === Closed
    def notLocked: Boolean = !locked

  }
}

@derive(eqv)
sealed trait ConsensusStatus[Artifact]

final case class CollectingFacilities[A](maybeFacilityInfo: Option[FacilityInfo[A]]) extends ConsensusStatus[A]
final case class CollectingProposals[A](
  majorityTrigger: ConsensusTrigger,
  maybeProposalInfo: Option[ProposalInfo[A]],
  maybeLastArtifact: Option[Signed[A]],
  facilitatorsHash: Hash
) extends ConsensusStatus[A]
final case class CollectingSignatures[A](majorityArtifactHash: Hash, majorityTrigger: ConsensusTrigger, facilitatorsHash: Hash)
    extends ConsensusStatus[A]
final case class Finished[A](signedMajorityArtifact: Signed[A], majorityTrigger: ConsensusTrigger, facilitatorsHash: Hash)
    extends ConsensusStatus[A]

object ConsensusStatus {
  implicit def showInstance[A]: Show[ConsensusStatus[A]] = {
    case CollectingFacilities(maybeFacilityInfo) =>
      s"CollectingFacilities{maybeFacilityInfo=${maybeFacilityInfo.show}}"
    case CollectingProposals(majorityTrigger, maybeProposalInfo, _, _) =>
      s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, maybeProposalInfo=${maybeProposalInfo.show}, maybeLastArtifact=***}"
    case CollectingSignatures(majorityArtifactHash, majorityTrigger, _) =>
      s"CollectingSignatures{majorityArtifactHash=${majorityArtifactHash.show}, ${majorityTrigger.show}}"
    case Finished(_, majorityTrigger, _) =>
      s"Finished{signedMajorityArtifact=***, majorityTrigger=${majorityTrigger.show}}"
  }
}

@derive(eqv)
case class FacilityInfo[A](lastSignedArtifact: Signed[A], maybeTrigger: Option[ConsensusTrigger], facilitatorsHash: Hash)
object FacilityInfo {
  implicit def showInstance[A]: Show[FacilityInfo[A]] = f => s"FacilityInfo{lastSignedArtifact=***, maybeTrigger=${f.maybeTrigger.show}}"
}

@derive(eqv)
case class ProposalInfo[A](proposalArtifact: A, proposalArtifactHash: Hash)
object ProposalInfo {
  implicit def showInstance[A]: Show[ProposalInfo[A]] = pi =>
    s"ProposalInfo{proposalArtifact=***, proposalArtifactHash=${pi.proposalArtifactHash.show}}"
}

@derive(eqv, show)
sealed trait LockStatus

case object Open extends LockStatus
case object Closed extends LockStatus
case object Reopened extends LockStatus
