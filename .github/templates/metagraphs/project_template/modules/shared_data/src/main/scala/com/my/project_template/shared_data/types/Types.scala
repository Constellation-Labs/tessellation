package com.my.project_template.shared_data.types

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.constellationnetwork.currency.dataApplication.{DataCalculatedState, DataOnChainState, DataUpdate}
import io.constellationnetwork.schema.address.Address

object Types {
  @derive(decoder, encoder)
  case class DeviceCalculatedState(
    usages: UsageUpdate
  )

  @derive(decoder, encoder)
  case class UsageUpdate(
    address    : Address,
    usage    : Long
  ) extends DataUpdate

  @derive(decoder, encoder)
  case class UsageUpdateState(
    updates: List[UsageUpdate]
  ) extends DataOnChainState

  @derive(decoder, encoder)
  case class UsageUpdateCalculatedState(
    devices: Map[Address, DeviceCalculatedState]
  ) extends DataCalculatedState
}
