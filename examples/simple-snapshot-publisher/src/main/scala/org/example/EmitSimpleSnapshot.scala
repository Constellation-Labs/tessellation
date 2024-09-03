package org.example

import io.constellationnetwork.kernel.Ω
import io.constellationnetwork.security.hash.Hash

case class EmitSimpleSnapshot(lastSnapshotHash: Hash) extends Ω
