package org.tessellation.kernel

import org.tessellation.security.hash.Hash

trait StateChannelSnapshot extends Ω {
  val lastSnapshotHash: Hash
}
