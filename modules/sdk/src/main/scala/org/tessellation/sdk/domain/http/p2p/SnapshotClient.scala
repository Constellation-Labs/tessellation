package org.tessellation.sdk.domain.http.p2p

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

trait SnapshotClient[F[_], S <: Snapshot[_, _]] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]]
}
