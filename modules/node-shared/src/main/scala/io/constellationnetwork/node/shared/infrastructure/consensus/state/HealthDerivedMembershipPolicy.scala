package io.constellationnetwork.node.shared.infrastructure.consensus.state

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.ActiveFacilitatorAdmission
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

/** Layer policy for membership changes derived from transient health observations or quorum-certified evidence.
  *
  * Global L0 never removes a signing lease from node-local Facility arrival, withdrawal timing, or timeout observations. Its certified
  * policy may separately accept an EvictionCertificate after the Proposal path validates the target, tip, committee hash, Core witness
  * pool, quorum, and signatures. Currency L0 retains the legacy automatic-removal behavior.
  *
  * This policy changes behavior only. It is not serialized, hashed, or copied into consensus state.
  */
sealed trait HealthDerivedMembershipPolicy extends Product with Serializable {

  def allowsAutomaticRemoval: Boolean
  def acceptsEvictionCertificates: Boolean

  /** Filter both newly observed and outcome-carried Facility removals through the layer policy. */
  final def persistentFacilityRemovals(healthDerivedPeers: Set[PeerId]): Set[PeerId] =
    if (allowsAutomaticRemoval) healthDerivedPeers else Set.empty

  final def acceptedEvictionTargets(certifiedTargets: Set[PeerId]): Set[PeerId] =
    if (acceptsEvictionCertificates) certifiedTargets else Set.empty

  /** Canonical facilitator source at a certified-view or signature boundary.
    *
    * Retain mode restores the frozen round-start committee so node-local withdrawal timing cannot survive a view change or alter a
    * facilitator hash. Legacy mode preserves the mutable active set byte-for-byte.
    */
  final def canonicalFacilitators(activeFacilitators: List[PeerId], roundStartFacilitators: List[PeerId]): List[PeerId] =
    if (allowsAutomaticRemoval) activeFacilitators else roundStartFacilitators

  /** Apply a Proposal's already-validated eviction certificates without allowing node-local health observations to affect membership. */
  final def canonicalFacilitatorsAfterCertifiedEviction(
    activeFacilitators: List[PeerId],
    roundStartFacilitators: List[PeerId],
    certifiedTargets: Set[PeerId]
  ): List[PeerId] =
    if (certifiedTargets.nonEmpty && acceptsEvictionCertificates) activeFacilitators
    else canonicalFacilitators(activeFacilitators, roundStartFacilitators)

  /** Leader pool after a certified VCC/TC. GL0 must not narrow frozen Core with node-local withdrawal observations. */
  def certifiedViewChangeLeaderPool(
    coreFacilitators: List[PeerId],
    activeFacilitators: List[PeerId],
    roundStartFacilitators: List[PeerId]
  ): List[PeerId]

  def timeoutMembership(
    facilitators: List[PeerId],
    coreFacilitators: List[PeerId],
    roundStartFacilitators: List[PeerId],
    timeoutVoters: Set[PeerId],
    shrinkFloor: Int
  ): HealthDerivedMembershipPolicy.TimeoutMembership
}

object HealthDerivedMembershipPolicy {

  final case class TimeoutMembership(
    facilitators: List[PeerId],
    coreFacilitators: List[PeerId],
    leaderPool: List[PeerId],
    evaluatedActive: List[PeerId],
    shrinkApplied: Boolean,
    shrinkEvaluated: Boolean,
    exclusionCount: Int,
    recentSignerPoolSize: Int
  )

  /** Conservative Global L0 bridge.
    *
    * A timeout certificate advances the view, but the frozen signing leases and Core classification remain unchanged. The next leader comes
    * from frozen Core, with the frozen round-start committee as fallback only when Core is empty. A locally withdrawn or silent Core peer
    * may therefore consume a view, but every honest node retains the same deterministic leader pool and facilitator-hash source. Filtering
    * Core or signature membership through the mutable active set would let withdrawal-observation timing split nodes.
    */
  case object RetainSigningLeases extends HealthDerivedMembershipPolicy {
    val allowsAutomaticRemoval: Boolean = false
    val acceptsEvictionCertificates: Boolean = false

    def certifiedViewChangeLeaderPool(
      coreFacilitators: List[PeerId],
      activeFacilitators: List[PeerId],
      roundStartFacilitators: List[PeerId]
    ): List[PeerId] = leaderPool(coreFacilitators, roundStartFacilitators)

    def timeoutMembership(
      facilitators: List[PeerId],
      coreFacilitators: List[PeerId],
      roundStartFacilitators: List[PeerId],
      timeoutVoters: Set[PeerId],
      shrinkFloor: Int
    ): TimeoutMembership = {
      val canonical = canonicalFacilitators(facilitators, roundStartFacilitators)
      TimeoutMembership(
        facilitators = canonical,
        coreFacilitators = coreFacilitators,
        // Do not filter frozen Core through the node-local active/withdrawn view. A dead slot may
        // consume a view, but all nodes rotate through the same leader pool.
        leaderPool = certifiedViewChangeLeaderPool(coreFacilitators, facilitators, roundStartFacilitators),
        evaluatedActive = canonical,
        shrinkApplied = false,
        shrinkEvaluated = false,
        exclusionCount = 0,
        recentSignerPoolSize = 0
      )
    }
  }

  /** Global L0 policy: retain leases across node-local health observations, but accept an explicitly certified Tier-1 replacement.
    *
    * Timeout and Facility-removal behavior is identical to `RetainSigningLeases`. The only removal authority added here is an
    * EvictionCertificate whose target, tip binding, facilitator hash, Core witness pool, quorum, and signatures are validated by the normal
    * Proposal path.
    */
  case object CertifiedEvictionOnly extends HealthDerivedMembershipPolicy {
    val allowsAutomaticRemoval: Boolean = false
    val acceptsEvictionCertificates: Boolean = true

    def certifiedViewChangeLeaderPool(
      coreFacilitators: List[PeerId],
      activeFacilitators: List[PeerId],
      roundStartFacilitators: List[PeerId]
    ): List[PeerId] = leaderPool(coreFacilitators, roundStartFacilitators)

    def timeoutMembership(
      facilitators: List[PeerId],
      coreFacilitators: List[PeerId],
      roundStartFacilitators: List[PeerId],
      timeoutVoters: Set[PeerId],
      shrinkFloor: Int
    ): TimeoutMembership = {
      val canonical = canonicalFacilitators(facilitators, roundStartFacilitators)
      TimeoutMembership(
        facilitators = canonical,
        coreFacilitators = coreFacilitators,
        leaderPool = certifiedViewChangeLeaderPool(coreFacilitators, facilitators, roundStartFacilitators),
        evaluatedActive = canonical,
        shrinkApplied = false,
        shrinkEvaluated = false,
        exclusionCount = 0,
        recentSignerPoolSize = 0
      )
    }
  }

  /** Exact rc.6 behavior retained for Currency L0. */
  case object LegacyAutomaticRemoval extends HealthDerivedMembershipPolicy {
    val allowsAutomaticRemoval: Boolean = true
    val acceptsEvictionCertificates: Boolean = true

    def certifiedViewChangeLeaderPool(
      coreFacilitators: List[PeerId],
      activeFacilitators: List[PeerId],
      roundStartFacilitators: List[PeerId]
    ): List[PeerId] = leaderPool(coreFacilitators, activeFacilitators)

    def timeoutMembership(
      facilitators: List[PeerId],
      coreFacilitators: List[PeerId],
      roundStartFacilitators: List[PeerId],
      timeoutVoters: Set[PeerId],
      shrinkFloor: Int
    ): TimeoutMembership = {
      val certifiedShrink = ActiveFacilitatorAdmission.fromCertifiedTimeout(
        selected = facilitators,
        recentSigners = SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]],
        timeoutVoters = timeoutVoters,
        minActiveSize = shrinkFloor
      )
      val shrunk = certifiedShrink.recentFilterApplied
      val effective = certifiedShrink.active

      TimeoutMembership(
        facilitators = if (shrunk) effective else facilitators,
        coreFacilitators = if (shrunk) effective else coreFacilitators,
        // Preserve the legacy TC behavior exactly: leader selection used the certified active
        // result even when it did not persist a shrink.
        leaderPool = leaderPool(effective, effective),
        evaluatedActive = effective,
        shrinkApplied = shrunk,
        shrinkEvaluated = true,
        exclusionCount = certifiedShrink.exclusions.size,
        recentSignerPoolSize = certifiedShrink.recentSignerPoolSize
      )
    }
  }

  private def leaderPool(coreFacilitators: List[PeerId], facilitators: List[PeerId]): List[PeerId] =
    if (coreFacilitators.nonEmpty) coreFacilitators else facilitators
}
