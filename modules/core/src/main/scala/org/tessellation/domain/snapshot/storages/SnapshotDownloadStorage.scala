package org.tessellation.domain.snapshot.storages

import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait SnapshotDownloadStorage[F[_]] {
  def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]
  def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]]

  def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]
  def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def deletePersisted(ordinal: SnapshotOrdinal): F[Unit]

  def isPersisted(hash: Hash): F[Boolean]

  def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit]
  def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit]

  def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]
  def backupPersistedAbove(ordinal: SnapshotOrdinal): F[Unit]
}
