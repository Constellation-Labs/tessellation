package org.tessellation.dag.snapshot

import org.tessellation.security.hash.Hash

case class StateChannelSnapshotWrapper(
  lastSnapshotHash: Hash,
  content: Array[Byte]
)
