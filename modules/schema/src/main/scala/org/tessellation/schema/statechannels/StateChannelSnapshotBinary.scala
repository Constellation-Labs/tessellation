package org.tessellation.schema.statechannels

import cats.Show
import cats.syntax.show._

import org.tessellation.schema.arrayOrder
import org.tessellation.schema.security.hash.Hash

import derevo.cats.{eqv, order}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, order, eqv)
case class StateChannelSnapshotBinary(
  lastSnapshotHash: Hash,
  content: Array[Byte]
)

object StateChannelSnapshotBinary {
  implicit val show: Show[StateChannelSnapshotBinary] = s => s"StateChannelSnapshotBinary(lastSnapshotHash=${s.lastSnapshotHash.show})"
}
