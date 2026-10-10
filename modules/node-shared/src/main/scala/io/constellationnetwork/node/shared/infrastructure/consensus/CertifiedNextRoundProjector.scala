package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Single deterministic projection used both before certifying a v35 membership value and while creating the next round.
  *
  * Successor membership is exactly (B \ (D u E)) u A: B the frozen committee, E certified evictions, A certified admissions, and D the
  * deterministic exits, members no longer in the configured seedlist or no longer collateral-eligible in the independently rebuilt next
  * context. Incumbents are never truncated by hash order: the configured cap only rejects a transition that would grow past max(cap, |B|).
  * A certified admission is invalid unless it is eligible, and an empty successor is invalid.
  */
object CertifiedNextRoundProjector {

  final case class Projection(
    projectedCommittee: List[PeerId],
    eligibleCommittee: List[PeerId],
    selectedCommittee: List[PeerId],
    // Deterministic exits (D): incumbents removed because they left the seedlist or lost collateral eligibility.
    exits: List[PeerId]
  )

  def projectTransition(
    roundStartCommittee: List[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    maxChanges: Int,
    unpairedEvictionFloor: Option[Int] = None
  ): Either[String, List[PeerId]] =
    CertifiedMembershipTransition.applyTo(roundStartCommittee, admittedPeers, evictedPeers, maxChanges, unpairedEvictionFloor)

  /** Admissions a proposal may carry without the successor exceeding max(cap, |B|). `removedCount` = |E u D|, the incumbents leaving
    * through certified evictions or deterministic exits; each frees one seat, so one-for-one replacements always fit.
    */
  def admissionCapacity(roundStartSize: Int, removedCount: Int, maxCommitteeSize: Option[Int]): Int =
    maxCommitteeSize.fold(Int.MaxValue) { cap =>
      math.max(0, math.max(cap, roundStartSize) - (roundStartSize - removedCount))
    }

  def project[F[_]: Monad](
    roundStartCommittee: List[PeerId],
    admittedPeers: Set[PeerId],
    evictedPeers: Set[PeerId],
    maxChanges: Int,
    seedlistPeerIds: Set[PeerId],
    isContextEligible: PeerId => F[Boolean],
    facilitatorSelector: FacilitatorSelector,
    unpairedEvictionFloor: Option[Int] = None
  ): F[Either[String, Projection]] =
    projectTransition(roundStartCommittee, admittedPeers, evictedPeers, maxChanges, unpairedEvictionFloor).traverse { projected =>
      projected
        .filterA(pid =>
          (seedlistPeerIds.isEmpty || seedlistPeerIds.contains(pid)).pure[F].flatMap {
            case false => false.pure[F]
            case true  => isContextEligible(pid)
          }
        )
        .map { eligible =>
          val eligibleSet = eligible.toSet
          val missingAdmissions = admittedPeers -- eligibleSet
          val exits = projected.filterNot(eligibleSet.contains)
          val capLimit = facilitatorSelector.getMaxCount.fold(Int.MaxValue)(cap => math.max(cap, roundStartCommittee.size))
          for {
            _ <- Either.cond(
              missingAdmissions.isEmpty,
              (),
              s"certified_membership_admission_not_next_round_eligible:${missingAdmissions.toList.sorted.map(_.value.value).mkString(",")}"
            )
            _ <- Either.cond(eligible.nonEmpty, (), "certified_membership_empty_successor")
            _ <- Either.cond(eligible.size <= capLimit, (), s"certified_membership_over_cap:${eligible.size}/$capLimit")
          } yield Projection(projected, eligible, eligible.sorted, exits.sorted)
        }
    }
      .map(_.flatMap(identity))
}
