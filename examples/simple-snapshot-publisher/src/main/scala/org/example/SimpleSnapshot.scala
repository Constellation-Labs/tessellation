package org.example

import io.constellationnetwork.kernel.StateChannelSnapshot
import io.constellationnetwork.security.hash.Hash

case class SimpleSnapshot(lastSnapshotHash: Hash) extends StateChannelSnapshot
