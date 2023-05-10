package org.tessellation.statechannel

import cats.Show
import cats.syntax.show._

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.schema.address.Address
import org.tessellation.schema.arrayOrder
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, order}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, order, eqv)
case class StateChannelSnapshotBinary(
  lastSnapshotHash: Hash,
  content: Array[Byte],
  fee: SnapshotFee
)

object StateChannelSnapshotBinary {

  implicit class StateChannelSnapshotBinaryOps(stateChannelSnapshotBinary: StateChannelSnapshotBinary) {

    def toAddress: Address = Address.fromBytes(stateChannelSnapshotBinary.content)
  }

  implicit val show: Show[StateChannelSnapshotBinary] = s =>
    s"StateChannelSnapshotBinary(lastSnapshotHash=${s.lastSnapshotHash.show}, fee=${s.fee.show})"
}
