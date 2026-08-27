package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.BinarySignature
import io.constellationnetwork.security.hash.Hash

/** Identity of one Currency L0 state-channel binary signing attempt.
  *
  * Binding the signature to the exact binary hash closes two replay domains that the legacy declaration left implicit: a same-key view
  * change and two locally-constructed binaries for the same unsigned Currency artifact.
  */
final case class BinarySignatureAttemptDomain(
  facilitatorsHash: Hash,
  lastSnapshotHash: Hash,
  binaryHash: Hash,
  view: Long,
  proposalHash: Hash
) {
  def contains(signature: BinarySignature): Boolean =
    signature.facilitatorsHash == facilitatorsHash &&
      signature.lastSnapshotHash == lastSnapshotHash &&
      signature.binaryHash == binaryHash &&
      signature.view == view &&
      signature.proposalHash == proposalHash
}
