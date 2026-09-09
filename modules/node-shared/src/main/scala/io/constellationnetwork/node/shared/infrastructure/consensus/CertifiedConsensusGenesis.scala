package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.syntax.eq._

import io.constellationnetwork.schema.SnapshotOrdinal

/** Boundary predicate for Global L0 certified consensus that is active from genesis.
  *
  * L0 consensus is initialized from the first incremental snapshot, not from the full genesis snapshot. The full genesis snapshot occupies
  * `SnapshotOrdinal.MinValue`; Global L0 therefore installs its incremental root at the following ordinal. Keeping that distinction here
  * prevents a regression to the incorrect `key == 0` check.
  *
  * This predicate grants no authority by itself. Global L0 must still reconstruct and validate its exact canonical root outcome before
  * accepting or persisting it.
  */
object CertifiedConsensusGenesis {
  val FirstIncrementalOrdinal: SnapshotOrdinal =
    SnapshotOrdinal.MinIncrementalValue

  def isActiveFromGenesis(certifiedConsensusActivationKey: Long): Boolean =
    certifiedConsensusActivationKey <= FirstIncrementalOrdinal.value.value

  /** The only certified expansion that may precede next-seat headroom.
    *
    * A from-genesis lineage starts with one canonical signer. Requiring two current-committee parent proofs before admitting its second
    * signer is circular because that second signer is not yet in the committee. The monotonic lineage fact, rather than the legacy
    * proof-window bootstrap flag, closes the exception after the first admission. It cannot activate on a mature ordinal-gated lineage or
    * re-arm after later degradation to one signer.
    */
  def allowsSingletonBootstrapExpansion(
    certifiedConsensusActive: Boolean,
    certifiedConsensusActivationKey: Long,
    currentCommitteeSize: Int,
    expandedBeyondSingleton: Boolean
  ): Boolean =
    certifiedConsensusActive &&
      isActiveFromGenesis(certifiedConsensusActivationKey) &&
      currentCommitteeSize == 1 &&
      !expandedBeyondSingleton

  /** Resolve the monotonic singleton-expansion fact carried by a certified lineage.
    *
    * Missing state is interpreted as `false` only for the exact canonical from-genesis singleton root. This permits historical root
    * outcomes written before the field existed to make their one necessary 1 -> 2 transition, while every mature, ordinal-gated, or
    * previously-expanded lineage fails closed to `true`. In particular, later degradation back to one signer can never re-arm the
    * exception.
    */
  def hasExpandedBeyondSingleton(
    certifiedConsensusActivationKey: Long,
    parentKey: SnapshotOrdinal,
    parentCommitteeSize: Int,
    carried: Option[Boolean]
  ): Boolean =
    carried.getOrElse(
      !isRootKey(certifiedConsensusActivationKey, parentKey) || parentCommitteeSize != 1
    )

  /** Advance the monotonic fact after a certified round. */
  def nextExpandedBeyondSingleton(
    certifiedConsensusActivationKey: Long,
    parentKey: SnapshotOrdinal,
    parentCommitteeSize: Int,
    carried: Option[Boolean],
    nextCommitteeSize: Int
  ): Boolean =
    hasExpandedBeyondSingleton(
      certifiedConsensusActivationKey,
      parentKey,
      parentCommitteeSize,
      carried
    ) || nextCommitteeSize > 1

  def isRootKey(certifiedConsensusActivationKey: Long, key: SnapshotOrdinal): Boolean =
    isActiveFromGenesis(certifiedConsensusActivationKey) && key === FirstIncrementalOrdinal
}
