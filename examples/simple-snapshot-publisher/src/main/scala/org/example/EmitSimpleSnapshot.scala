package org.example

import org.tessellation.kernel.Ω
import org.tessellation.security.hash.Hash

case class EmitSimpleSnapshot(lastSnapshotHash: Hash) extends Ω
