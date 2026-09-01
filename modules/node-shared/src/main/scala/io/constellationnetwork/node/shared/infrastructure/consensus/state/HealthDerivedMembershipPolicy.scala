package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId

/** Layer policy for membership changes derived from transient health observations.
  *
  * Global L0's conservative bridge creates no new health-derived removal debt. It retains leases not already excluded by carried debt or
  * administrative policy. Local Facility arrival, eviction votes, and timeout voters may still drive progress within a round, but they
  * cannot create a new reason to delete a peer from the next round's signing committee. Currency L0 has its own flat synchronous engine and
  * does not use this policy.
  *
  * This policy changes behavior only. It is not serialized, hashed, or copied into consensus state.
  */
sealed trait HealthDerivedMembershipPolicy extends Product with Serializable {

  def allowsAutomaticRemoval: Boolean

  /** Whether this layer may certify an eviction that changes the next round. Kept separate from current-round automatic mutation so a
    * future policy can permit quorum-certified N+1 changes without reintroducing Facility/timeout-driven shrink inside N.
    */
  def acceptsCertifiedNextRoundEvictions: Boolean

  /** Accept and assemble silent-lease evidence for a v35 atomic replacement. This is intentionally separate from automatic removal:
    * enabling it must never authorize a standalone eviction or mutate the current round.
    */
  def supportsCertifiedAtomicReplacement: Boolean

  /** Membership policy for a certified VCC/timeout transition.
    *
    * The pre-activation and certified GL0 paths both retain the frozen signing leases. Keeping the choice explicit at the shared transition
    * boundary prevents VCC and timeout paths from drifting if another GL0 policy is introduced later.
    */
  final def forCertifiedView(certifiedConsensusActive: Boolean): HealthDerivedMembershipPolicy =
    if (certifiedConsensusActive) HealthDerivedMembershipPolicy.RetainSigningLeases else this

  /** Filter both newly observed and outcome-carried Facility removals through the layer policy. */
  final def persistentFacilityRemovals(healthDerivedPeers: Set[PeerId]): Set[PeerId] =
    if (allowsAutomaticRemoval) healthDerivedPeers else Set.empty

  final def acceptedEvictionTargets(certifiedTargets: Set[PeerId]): Set[PeerId] =
    if (acceptsCertifiedNextRoundEvictions) certifiedTargets else Set.empty

  final def acceptsEvictionCertificates: Boolean = acceptsCertifiedNextRoundEvictions

  final def acceptsEvictionVotes: Boolean =
    acceptsCertifiedNextRoundEvictions || supportsCertifiedAtomicReplacement

  /** Runtime receipt gate. Legacy layers retain their existing pre-state vote behavior, while retain-mode Global L0 cannot accumulate
    * replacement evidence before v35 is active for the exact consensus key.
    */
  final def acceptsEvictionVotesAt(certifiedConsensusActive: Boolean): Boolean =
    acceptsCertifiedNextRoundEvictions || (supportsCertifiedAtomicReplacement && certifiedConsensusActive)

  final def allowsCertifiedAtomicReplacement(certifiedConsensusActive: Boolean): Boolean =
    supportsCertifiedAtomicReplacement && certifiedConsensusActive

  final def certifiedEvictionTargetsAllowed(certifiedTargets: Set[PeerId]): Boolean =
    certifiedTargets.isEmpty || acceptsCertifiedNextRoundEvictions

  /** Canonical facilitator source at a certified-view or signature boundary.
    *
    * Retain mode restores the frozen round-start committee so node-local withdrawal timing cannot survive a view change or alter a
    * facilitator hash. Legacy mode preserves the mutable active set byte-for-byte.
    */
  final def canonicalFacilitators(activeFacilitators: List[PeerId], roundStartFacilitators: List[PeerId]): List[PeerId] =
    if (allowsAutomaticRemoval) activeFacilitators else roundStartFacilitators

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
    val acceptsCertifiedNextRoundEvictions: Boolean = false
    val supportsCertifiedAtomicReplacement: Boolean = true

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

  private def leaderPool(coreFacilitators: List[PeerId], facilitators: List[PeerId]): List[PeerId] =
    if (coreFacilitators.nonEmpty) coreFacilitators else facilitators
}
