package org.tessellation.dag.snapshot

import cats.syntax.eq._
import cats.syntax.show._
import cats.{Eq, Show}

import org.tessellation.security.hash.Hash

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class StateChannelSnapshotBinary(
  lastSnapshotHash: Hash,
  content: Array[Byte]
)

object StateChannelSnapshotBinary {
  implicit val show: Show[StateChannelSnapshotBinary] = s => s"StateChannelSnapshotBinary(lastSnapshotHash=${s.lastSnapshotHash.show})"

  implicit val eq: Eq[StateChannelSnapshotBinary] = (x, y) => {
    implicit val arrayEq: Eq[Array[Byte]] = Eq.fromUniversalEquals[Array[Byte]]
    x.lastSnapshotHash === y.lastSnapshotHash && x.content === y.content
  }
}
