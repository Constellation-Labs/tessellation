package com.my.project_template.shared_data.types

import io.constellationnetwork.currency.dataApplication.{DataCalculatedState, DataOnChainState, DataUpdate}
import io.constellationnetwork.ext.refined.{decoderOf, encoderOf}
import io.constellationnetwork.schema.address.Address

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}

object Types {
  implicit val nonNegLongEncoder: Encoder[NonNegLong] =
    encoderOf[Long, NonNegative]

  implicit val nonNegLongDecoder: Decoder[NonNegLong] =
    decoderOf[Long, NonNegative]

  @derive(decoder, encoder)
  case class UsageUpdateInfo(
    deviceAddress: Address,
    deviceUsage: NonNegLong
  )

  @derive(decoder, encoder)
  case class DeviceCalculatedState(
    usages: UsageUpdateInfo
  )

  @derive(decoder, encoder)
  sealed trait UsageUpdate extends DataUpdate {
    val address: Address
    val usage: NonNegLong
  }

  @derive(decoder, encoder)
  case class UsageUpdateNoFee(
    address: Address,
    usage: NonNegLong
  ) extends UsageUpdate

  @derive(decoder, encoder)
  case class UsageUpdateWithFee(
    address: Address,
    usage: NonNegLong
  ) extends UsageUpdate

  @derive(decoder, encoder)
  case class UsageUpdateState(
    updates: List[UsageUpdate]
  ) extends DataOnChainState

  @derive(decoder, encoder)
  case class UsageUpdateCalculatedState(
    devices: Map[Address, DeviceCalculatedState]
  ) extends DataCalculatedState
}
