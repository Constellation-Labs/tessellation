package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.CertifiedProposalQC
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Local v35 vote lock over the complete certified ProposalValue hash.
  *
  * This deliberately does not reuse the legacy artifact-only VoteLock. The two hashes have different meanings and mixing them would make it
  * possible to treat an artifact QC as certification of the outcome envelope again.
  */
@derive(eqv, show, encoder, decoder)
final case class CertifiedVoteLock(
  highestVotedView: Option[Long],
  votedValueHashAtHighestView: Option[Hash],
  lockedQc: Option[CertifiedProposalQC]
) {

  def acceptVote(
    view: Long,
    valueHash: Hash,
    effectiveLockedQc: Option[CertifiedProposalQC]
  ): Either[VoteRejection, CertifiedVoteLock] =
    VoteLockRules
      .accept(
        VoteLockRules.State(highestVotedView, votedValueHashAtHighestView, lockedQc),
        view,
        valueHash,
        effectiveLockedQc
      )(
        _.value.committedView,
        _.valueHash
      )
      .map(state => CertifiedVoteLock(state.highestVotedView, state.votedHashAtHighestView, state.lockedQc))

  def withAdvancedQc(newQc: CertifiedProposalQC): CertifiedVoteLock = {
    val state = VoteLockRules
      .advance(VoteLockRules.State(highestVotedView, votedValueHashAtHighestView, lockedQc), newQc)(_.value.committedView)
    CertifiedVoteLock(state.highestVotedView, state.votedHashAtHighestView, state.lockedQc)
  }
}

object CertifiedVoteLock {
  val empty: CertifiedVoteLock = CertifiedVoteLock(None, None, None)

  def maxByView(
    left: Option[CertifiedProposalQC],
    right: Option[CertifiedProposalQC]
  ): Option[CertifiedProposalQC] =
    VoteLockRules.maxByView(left, right)(_.value.committedView)
}
