package org.tessellation.dag.snapshot

import cats.Show
import cats.syntax.show._

import org.tessellation.security.hash.Hash

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class StateChannelSnapshotBinary(
  lastSnapshotHash: Hash,
  content: Array[Byte]
)

object StateChannelSnapshotBinary {
  implicit val show: Show[StateChannelSnapshotBinary] = s =>
    s"StateChannelSnapshotBinary(lastSnapshotHash=${s.lastSnapshotHash.show})"
}
