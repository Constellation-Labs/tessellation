package org.tessellation.dag.l0.domain.snapshot.storages

import org.tessellation.schema._
import org.tessellation.security.Hasher
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait SnapshotDownloadStorage[F[_]] {
  def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]
  def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]

  def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]
  def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def deletePersisted(ordinal: SnapshotOrdinal): F[Unit]

  def isPersisted(hash: Hash): F[Boolean]

  def hasCorrectSnapshotInfo(ordinal: SnapshotOrdinal, proof: GlobalSnapshotStateProof)(implicit hasher: Hasher[F]): F[Boolean]
  def getHighestSnapshotInfoOrdinal(lte: SnapshotOrdinal): F[Option[SnapshotOrdinal]]
  def readCombined(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
  def persistSnapshotInfoWithCutoff(ordinal: SnapshotOrdinal, info: GlobalSnapshotInfo): F[Unit]

  def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit]
  def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]
  def writeGenesis(genesis: Signed[GlobalSnapshot]): F[Unit]

  def cleanupAbove(ordinal: SnapshotOrdinal): F[Unit]
}
