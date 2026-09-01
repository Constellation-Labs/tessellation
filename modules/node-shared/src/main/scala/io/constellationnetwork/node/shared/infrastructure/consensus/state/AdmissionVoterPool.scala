package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus
import io.constellationnetwork.schema.peer.PeerId

/** Shared voter-membership rule for admission certificate assembly and validation.
  *
  * Open expansion is Core-certified so Tier 1 cannot become a liveness dependency. Legacy penalty/probation readmission retains the wider
  * deterministic witness lane needed to recover from a degraded committee. Certified atomic membership requires Core certification for
  * every admission-only transition, including probation, because the admitted seat changes the value that Core certifies. The target never
  * votes for itself.
  */
object AdmissionVoterPool {

  /** A certified admission may be emitted only by a peer whose vote can count toward the certificate. Legacy probation recovery keeps its
    * wider witness lane, so this gate is intentionally inactive until Core-only certification is required.
    */
  def allowsVoteEmission(voter: PeerId, requireCoreCertification: Boolean, core: Set[PeerId]): Boolean =
    !requireCoreCertification || core.contains(voter)

  def requiredQuorum(coreSize: Int, quorumThresholdFraction: Double, requireCoreCertification: Boolean): Int =
    if (requireCoreCertification)
      CertifiedConsensus.requiredCoreQuorum(coreSize, quorumThresholdFraction)
    else math.max(1, QuorumPolicy.fromFraction(coreSize, quorumThresholdFraction))

  def select(
    target: PeerId,
    isProbationReadmission: Boolean,
    requireCoreCertification: Boolean,
    core: Set[PeerId],
    widerWitnessPool: Set[PeerId]
  ): Set[PeerId] =
    (if (isProbationReadmission && !requireCoreCertification) widerWitnessPool else core) - target
}
