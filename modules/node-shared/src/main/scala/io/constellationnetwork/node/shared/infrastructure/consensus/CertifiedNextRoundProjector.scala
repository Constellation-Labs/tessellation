package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Single deterministic projection used both before certifying a v35 membership value and while creating the next round.
  *
  * The projection owns the whole non-health-derived eligibility boundary: apply the certified transition, enforce the configured seedlist,
  * evaluate collateral against the independently rebuilt next context, and run the existing FacilitatorSelector with the new artifact hash
  * (the next round's entropy). A certified admission is invalid unless it survives this exact projection.
  */
object CertifiedNextRoundProjector {

  final case class Projection(
    projectedCommittee: List[PeerId],
    eligibleCommittee: List[PeerId],
    selectedCommittee: List[PeerId]
  )

  def projectTransition(
    roundStartCommittee: List[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    maxChanges: Int
  ): Either[String, List[PeerId]] =
    CertifiedMembershipTransition.applyTo(roundStartCommittee, admittedPeers, evictedPeers, maxChanges)

  def project[F[_]: Monad](
    roundStartCommittee: List[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    maxChanges: Int,
    seedlistPeerIds: Set[PeerId],
    isContextEligible: PeerId => F[Boolean],
    facilitatorSelector: FacilitatorSelector,
    nextRoundEntropy: Hash
  ): F[Either[String, Projection]] =
    projectValidatedTransition(
      projectTransition(roundStartCommittee, admittedPeers, evictedPeers, maxChanges),
      admittedPeers,
      seedlistPeerIds,
      isContextEligible,
      facilitatorSelector,
      nextRoundEntropy
    )

  private def projectValidatedTransition[F[_]: Monad](
    transition: Either[String, List[PeerId]],
    admittedPeers: Set[PeerId],
    seedlistPeerIds: Set[PeerId],
    isContextEligible: PeerId => F[Boolean],
    facilitatorSelector: FacilitatorSelector,
    nextRoundEntropy: Hash
  ): F[Either[String, Projection]] =
    transition.traverse { projected =>
      val seedlistEligible = projected.filter(pid => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(pid))
      seedlistEligible.filterA(isContextEligible).map { eligible =>
        val selected = facilitatorSelector.select(eligible, nextRoundEntropy)
        val missingAdmissions = admittedPeers -- selected.toSet

        Either.cond(
          missingAdmissions.isEmpty,
          Projection(projected, eligible, selected),
          s"certified_membership_admission_not_next_round_eligible:${missingAdmissions.toList.sorted.map(_.value.value).mkString(",")}"
        )
      }
    }
      .map(_.flatMap(identity))
}
