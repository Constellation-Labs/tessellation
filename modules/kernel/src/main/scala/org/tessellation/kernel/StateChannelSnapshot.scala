package org.tessellation.kernel

import org.tessellation.security.hash.Hash

trait StateChannelSnapshot extends Gistable[StateChannelSnapshot] {
  val lastSnapshotHash: Hash

  def gist: StateChannelSnapshot = StateChannelSnapshotGist(lastSnapshotHash)
}

case class StateChannelSnapshotGist(lastSnapshotHash: Hash) extends StateChannelSnapshot
