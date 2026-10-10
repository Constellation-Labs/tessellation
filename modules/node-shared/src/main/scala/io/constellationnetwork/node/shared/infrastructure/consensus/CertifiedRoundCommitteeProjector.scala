package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.ProposalValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

/** Pure, shared projection of the single frozen voting committee for one round.
  *
  * Every seated signing member votes; there is no Core/Tier-1 partition and no controller score, quality ratio, or leader-reported
  * observation that can remove a member's authority. Live state creation, proposal construction, and downloaded-outcome reconstruction
  * share this projection so they cannot drift.
  */
object CertifiedRoundCommitteeProjector {

  final case class Projection(
    expansionAllowed: Boolean,
    maxExpansionThisRound: Int,
    votingCommittee: List[PeerId]
  )

  /** Complete next-round projection from an independently trusted certified parent. */
  final case class FromCertifiedParent(
    nextRound: CertifiedNextRoundProjector.Projection,
    committee: Projection
  )

  /** Construct a live round from the parent QC's exact continuation authority. */
  def fromCertifiedAuthority(
    key: SnapshotOrdinal,
    authority: CertifiedConsensus.CertifiedRoundAuthorityV1,
    config: ConsensusConfig
  ): Projection =
    project(key, authority.facilitators.toSortedSet.toList, config)

  def fromCertifiedParent[F[_]: Monad](
    key: SnapshotOrdinal,
    parentValue: ProposalValue,
    config: ConsensusConfig,
    seedlistPeerIds: Set[PeerId],
    isContextEligible: PeerId => F[Boolean],
    facilitatorSelector: FacilitatorSelector
  ): F[Either[String, FromCertifiedParent]] =
    CertifiedNextRoundProjector
      .project[F](
        parentValue.roundStartFacilitators.toSortedSet.toList,
        parentValue.admittedPeers.toSet,
        parentValue.evictedPeers.toSet,
        config.activeAdmissionMaxExpansionPerRound,
        seedlistPeerIds,
        isContextEligible,
        facilitatorSelector
      )
      .map(_.map(nextRound => FromCertifiedParent(nextRound, project(key, nextRound.selectedCommittee, config))))

  def project(
    key: SnapshotOrdinal,
    selectedFacilitators: List[PeerId],
    config: ConsensusConfig,
    withdrawnPeers: Set[PeerId] = Set.empty
  ): Projection = {
    val expansionAllowed = AdmissionCadence.expansionAllowedAtOrdinal(
      key.value.value,
      config.activeAdmissionExpansionIntervalRounds
    )

    Projection(
      expansionAllowed = expansionAllowed,
      maxExpansionThisRound = if (expansionAllowed) config.activeAdmissionMaxExpansionPerRound else 0,
      votingCommittee = selectedFacilitators.filterNot(withdrawnPeers.contains).distinct.sorted
    )
  }
}
