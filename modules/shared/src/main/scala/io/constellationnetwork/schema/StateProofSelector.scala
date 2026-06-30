package io.constellationnetwork.schema

/** Represents the snapshot format era for determining which types to use.
  *
  * LegacyFormat: GlobalIncrementalSnapshotV2 + GlobalSnapshotInfoV3 + GlobalSnapshotStateProof (16 individual hash fields)
  * MerklePatriciaFormat: GlobalIncrementalSnapshot + GlobalSnapshotInfo + GlobalSnapshotStateProof (single MPT root)
  */
sealed trait SnapshotFormat
case object LegacyFormat extends SnapshotFormat
case object MerklePatriciaFormat extends SnapshotFormat

/** Selects the appropriate snapshot format based on ordinal.
  *
  * This is used to decouple the snapshot format selection from the hash scheme (Kryo vs JSON), since these transitions happened at
  * different ordinals on mainnet.
  */
trait StateProofSelector {
  def select(ordinal: SnapshotOrdinal): SnapshotFormat

  /** Whether the MPT state proof should additionally carry per-sub-trie (per `GlobalStateFieldId`) Merkle roots in its otherwise-`None`
    * fields, so a diverging node can see WHICH field's sub-trie diverged rather than only that the overall `mptRoot` differs. This changes
    * the SIGNED proof bytes, so it must be ordinal-gated and computed identically on every node. Defaults to `false` (inert) -- only
    * `GlobalStateProofSelector` enables it, and only at/above a configured activation ordinal (default `MaxValue` = never).
    */
  def subTrieRootsEnabled(ordinal: SnapshotOrdinal): Boolean = false
}

/** Global snapshot selector - uses configured ordinal boundary for MPT migration.
  *
  * `subTrieRootsActivationOrdinal` gates the per-sub-trie-root proof fields (see `subTrieRootsEnabled`); it defaults to `MaxValue` so the
  * feature is inert until a deliberate, coordinated cold cutover sets it (the proof is a signed artifact).
  */
class GlobalStateProofSelector(
  lastLegacyStateProofOrdinal: SnapshotOrdinal,
  subTrieRootsActivationOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(Long.MaxValue)
) extends StateProofSelector {
  def select(ordinal: SnapshotOrdinal): SnapshotFormat =
    if (ordinal.value.value <= lastLegacyStateProofOrdinal.value.value) LegacyFormat else MerklePatriciaFormat

  override def subTrieRootsEnabled(ordinal: SnapshotOrdinal): Boolean =
    ordinal.value.value >= subTrieRootsActivationOrdinal.value.value
}

object GlobalStateProofSelector {
  def apply(
    lastLegacyStateProofOrdinal: SnapshotOrdinal,
    subTrieRootsActivationOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(Long.MaxValue)
  ): GlobalStateProofSelector =
    new GlobalStateProofSelector(lastLegacyStateProofOrdinal, subTrieRootsActivationOrdinal)
}

/** Currency snapshot selector - no MPT migration currently (always uses legacy format) */
class CurrencyStateProofSelector extends StateProofSelector {
  def select(ordinal: SnapshotOrdinal): SnapshotFormat = LegacyFormat
}

object CurrencyStateProofSelector {
  val instance: CurrencyStateProofSelector = new CurrencyStateProofSelector()
}
