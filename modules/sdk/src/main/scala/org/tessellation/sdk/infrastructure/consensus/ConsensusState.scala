package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class ConsensusState[Key, Artifact](
  key: Key,
  facilitators: List[PeerId],
  lastKeyAndArtifact: (Key, Artifact),
  status: ConsensusStatus[Artifact]
)

@derive(eqv, show)
sealed trait ConsensusStatus[Artifact]

final case class Facilitated[A]() extends ConsensusStatus[A]
final case class ProposalMade[A](proposalHash: Hash, proposalArtifact: A) extends ConsensusStatus[A]
final case class MajoritySelected[A](majorityHash: Hash) extends ConsensusStatus[A]
final case class MajoritySigned[A](signedMajorityArtifact: Signed[A]) extends ConsensusStatus[A]
