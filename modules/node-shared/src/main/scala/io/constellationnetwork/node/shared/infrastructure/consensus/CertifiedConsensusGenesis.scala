package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.syntax.eq._

import io.constellationnetwork.schema.SnapshotOrdinal

/** Shared boundary predicate for certified consensus that is active from genesis.
  *
  * L0 consensus is initialized from the first incremental snapshot, not from the full genesis snapshot. The full genesis snapshot occupies
  * `SnapshotOrdinal.MinValue`; both DAG and Currency therefore install their independently created incremental root at the following
  * ordinal. Keeping that distinction here prevents the layers from drifting back to the incorrect `key == 0` check.
  *
  * This predicate grants no authority by itself. Each layer must still reconstruct and validate its exact canonical root outcome before
  * accepting or persisting it.
  */
object CertifiedConsensusGenesis {
  val FirstIncrementalOrdinal: SnapshotOrdinal =
    SnapshotOrdinal.MinIncrementalValue

  def isRootKey(certifiedConsensusActivationKey: Long, key: SnapshotOrdinal): Boolean =
    certifiedConsensusActivationKey <= FirstIncrementalOrdinal.value.value && key === FirstIncrementalOrdinal
}
