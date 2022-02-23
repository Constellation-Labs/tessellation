package org.example

import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.security.hash.Hash

case class SimpleSnapshot(lastSnapshotHash: Hash) extends StateChannelSnapshot
