package io.constellationnetwork.statechannel

import cats.Show
import cats.syntax.show._

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.arrayOrder
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, order}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary

@derive(encoder, decoder, order, eqv, arbitrary)
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
