package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId

/** Selects the signer pool for an EvictionCertificate.
  *
  * A Tier-1 signing-participation eviction is Core-attested: Tier 1 is deliberately outside the liveness machinery and cannot be made
  * necessary to replace a silent Tier-1 signing lease. Existing Core-target stall eviction retains the wider deterministic witness pool
  * because that lane is recovery machinery for a damaged Core committee.
  */
object EvictionVoterPool {

  def select(
    target: PeerId,
    isTier1Target: Boolean,
    core: Set[PeerId],
    widerWitnessPool: Set[PeerId]
  ): Set[PeerId] =
    if (isTier1Target) core - target
    else widerWitnessPool - target
}
