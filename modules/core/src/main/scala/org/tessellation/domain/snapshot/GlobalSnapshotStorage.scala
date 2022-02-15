package org.tessellation.domain.snapshot

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal, StateChannelSnapshotWrapper}
import org.tessellation.schema.address.Address

trait GlobalSnapshotStorage[F[_]] {

  def save(globalSnapshot: GlobalSnapshot): F[Unit]

  def getLast: F[GlobalSnapshot]

  def get(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshot]]

  def getStateChannelSnapshotUntilOrdinal(
    ordinal: SnapshotOrdinal
  )(address: Address): F[Option[StateChannelSnapshotWrapper]]

}
