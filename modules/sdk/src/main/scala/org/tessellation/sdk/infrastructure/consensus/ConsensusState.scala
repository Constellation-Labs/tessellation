package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.syntax.eq._
import cats.syntax.option._
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.kind._
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv)
case class ConsensusState[Key, Artifact](
  key: Key,
  lastKey: Key,
  facilitators: List[PeerId],
  status: ConsensusStatus[Artifact],
  createdAt: FiniteDuration,
  removedFacilitators: Set[PeerId] = Set.empty,
  withdrawnFacilitators: Set[PeerId] = Set.empty,
  lockStatus: LockStatus = Open,
  spreadAckKinds: Set[PeerDeclarationKind] = Set.empty
)

object ConsensusState {
  implicit def showInstance[K: Show, A]: Show[ConsensusState[K, A]] = { cs =>
    s"""ConsensusState{
       |key=${cs.key.show},
       |lockStatus=${cs.lockStatus.show},
       |facilitatorCount=${cs.facilitators.size.show},
       |removedFacilitators=${cs.removedFacilitators.show},
       |withdrawnFacilitators=${cs.withdrawnFacilitators.show},
       |spreadAckKinds=${cs.spreadAckKinds.show},
       |status=${cs.status.show}
       |}""".stripMargin.replace(",\n", ", ")
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

final case class CollectingFacilities[A](
  maybeTrigger: Option[ConsensusTrigger],
  lastSignedArtifact: Signed[A],
  facilitatorsHash: Hash
) extends ConsensusStatus[A]
final case class CollectingProposals[A](
  majorityTrigger: ConsensusTrigger,
  proposalInfo: ProposalInfo[A],
  lastSignedArtifact: Signed[A],
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A]
final case class CollectingSignatures[A](
  majorityArtifactHash: Hash,
  majorityTrigger: ConsensusTrigger,
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A]

@derive(encoder, decoder)
final case class Finished[A](
  signedMajorityArtifact: Signed[A],
  majorityTrigger: ConsensusTrigger,
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A]

object ConsensusStatus {
  implicit def showInstance[A]: Show[ConsensusStatus[A]] = {
    case CollectingFacilities(maybeTrigger, _, facilitatorsHash) =>
      s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}}"
    case CollectingProposals(majorityTrigger, maybeProposalInfo, _, candidates, facilitatorsHash) =>
      s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, maybeProposalInfo=${maybeProposalInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
    case CollectingSignatures(majorityArtifactHash, majorityTrigger, candidates, facilitatorsHash) =>
      s"CollectingSignatures{majorityArtifactHash=${majorityArtifactHash.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
    case Finished(_, majorityTrigger, candidates, facilitatorsHash) =>
      s"Finished{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
  }
}

@derive(eqv)
case class ProposalInfo[A](proposalArtifact: A, proposalArtifactHash: Hash)
object ProposalInfo {
  implicit def showInstance[A]: Show[ProposalInfo[A]] = pi => s"ProposalInfo{proposalArtifactHash=${pi.proposalArtifactHash.show}}"
}

@derive(eqv, show)
sealed trait LockStatus

case object Open extends LockStatus
case object Closed extends LockStatus
case object Reopened extends LockStatus
