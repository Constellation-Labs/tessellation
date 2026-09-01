package io.constellationnetwork.dag.l0.domain.snapshot.storages

import io.constellationnetwork.schema._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

trait SnapshotDownloadStorage[F[_]] {
  def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]
  def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]

  def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]
  def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def deletePersisted(ordinal: SnapshotOrdinal): F[Unit]

  /** Return true only when the content-addressed hash file, ordinal index, and derived snapshot-info form a usable replay anchor.
    * Implementations may repair the narrow torn-write case where exact bytes exist under `hash` but the ordinal hardlink is absent.
    */
  def ensurePersistedAnchor(hash: Hash, ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Boolean]

  def hasCorrectSnapshotInfo(ordinal: SnapshotOrdinal, proof: GlobalSnapshotStateProof)(implicit hasher: Hasher[F]): F[Boolean]
  def getHighestSnapshotInfoOrdinal(lte: SnapshotOrdinal): F[Option[SnapshotOrdinal]]
  def readCombined(
    ordinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F], stateProofSelector: StateProofSelector): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]

  /** Read and state-proof-validate a persisted snapshot/context pair without synchronizing or otherwise mutating application storage.
    *
    * Consensus lineage preflight uses this method before it has accepted a peer-supplied outcome. `readCombined` remains the application
    * recovery path and may synchronize the MPT or self-heal invalid files.
    */
  def readCombinedValidated(
    ordinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F], stateProofSelector: StateProofSelector): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]

  /** Read and validate the persisted pair while deriving the context proof with an explicitly selected source ordinal.
    *
    * This differs from `readCombinedValidated` for the first incremental after a full-snapshot checkpoint: its artifact is stored at the
    * next ordinal, while its context and state proof come from the full snapshot. Development's canonical root is the 0 -> 1 instance;
    * public historical checkpoints are non-zero. Ordinary snapshots must pass their own ordinal for both arguments.
    */
  def readCombinedValidatedAtProofOrdinal(
    ordinal: SnapshotOrdinal,
    proofOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F], stateProofSelector: StateProofSelector): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
  def persistSnapshotInfoWithCutoff(ordinal: SnapshotOrdinal, info: GlobalSnapshotInfo): F[Unit]

  def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit]
  def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]
  def writeGenesis(genesis: Signed[GlobalSnapshot]): F[Unit]

  def cleanupAbove(ordinal: SnapshotOrdinal): F[Unit]
}
