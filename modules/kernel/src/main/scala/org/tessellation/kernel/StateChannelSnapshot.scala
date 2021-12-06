package org.tessellation.kernel

import org.tessellation.security.hash.Hash

trait StateChannelSnapshot {
  val lastSnapshotHash: Hash
}
