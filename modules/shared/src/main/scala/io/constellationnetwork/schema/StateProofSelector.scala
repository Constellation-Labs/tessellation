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
}

/** Global snapshot selector - uses configured ordinal boundary for MPT migration */
class GlobalStateProofSelector(lastLegacyStateProofOrdinal: SnapshotOrdinal) extends StateProofSelector {
  def select(ordinal: SnapshotOrdinal): SnapshotFormat =
    if (ordinal.value.value <= lastLegacyStateProofOrdinal.value.value) LegacyFormat else MerklePatriciaFormat
}

object GlobalStateProofSelector {
  def apply(lastLegacyStateProofOrdinal: SnapshotOrdinal): GlobalStateProofSelector =
    new GlobalStateProofSelector(lastLegacyStateProofOrdinal)
}

/** Currency snapshot selector - no MPT migration currently (always uses legacy format) */
class CurrencyStateProofSelector extends StateProofSelector {
  def select(ordinal: SnapshotOrdinal): SnapshotFormat = LegacyFormat
}

object CurrencyStateProofSelector {
  val instance: CurrencyStateProofSelector = new CurrencyStateProofSelector()
}
