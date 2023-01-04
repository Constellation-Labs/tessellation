package org.tessellation.statechannel

import org.tessellation.security.hash.Hash

trait StateChannelSnapshot {
  val lastSnapshotHash: Hash
}
