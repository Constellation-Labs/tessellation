package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.MajoritySignature
import io.constellationnetwork.security.hash.Hash

/** Existing-wire identity of one MajoritySignature attempt.
  *
  * This is deliberately an ordinary typed value, not a new hash or serialization domain. Consensus declarations are already keyed by the
  * round key in storage, so the remaining fields below are sufficient to keep an old same-key attempt from satisfying the declaration gate
  * for a newer view/proposal. The cryptographic proof is still verified against `proposalHash` by the layer advancer.
  */
final case class SignatureAttemptDomain(
  facilitatorsHash: Hash,
  lastSnapshotHash: Hash,
  view: Long,
  proposalHash: Hash
) {
  def contains(signature: MajoritySignature): Boolean =
    signature.facilitatorsHash == facilitatorsHash &&
      signature.lastSnapshotHash == lastSnapshotHash &&
      signature.view == view &&
      signature.proposalHash == proposalHash
}
