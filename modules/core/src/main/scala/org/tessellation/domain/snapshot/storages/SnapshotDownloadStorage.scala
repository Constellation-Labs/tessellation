package org.tessellation.domain.snapshot.storages

import org.tessellation.schema._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait SnapshotDownloadStorage[F[_]] {
  def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]
  def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]

  def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]
  def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def deletePersisted(ordinal: SnapshotOrdinal): F[Unit]

  def isPersisted(hash: Hash): F[Boolean]

  def hasSnapshotInfo(ordinal: SnapshotOrdinal): F[Boolean]
  def hasCorrectSnapshotInfo(ordinal: SnapshotOrdinal, proof: GlobalSnapshotStateProof): F[Boolean]
  def getHighestSnapshotInfo(lte: SnapshotOrdinal): F[Option[SnapshotOrdinal]]
  def readCombined(ordinal: SnapshotOrdinal): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
  def persistSnapshotInfo(ordinal: SnapshotOrdinal, info: GlobalSnapshotInfo): F[Unit]

  def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit]
  def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]
  def writeGenesis(genesis: Signed[GlobalSnapshot]): F[Unit]

  def cleanupAbove(ordinal: SnapshotOrdinal): F[Unit]
}
