package com.my.project_template.shared_data.combiners

import com.my.project_template.shared_data.types.Types._
import org.tessellation.currency.dataApplication.DataState
import org.tessellation.schema.address.Address
import org.tessellation.security.signature.Signed

object Combiners {
  private def getUpdatedDeviceUsage(
    usage  : Long,
    acc    : DataState[UsageUpdateState, UsageUpdateCalculatedState],
    address: Address
  ): UsageUpdate = {
    val deviceCalculatedState = acc.calculated.devices.getOrElse(
      address,
      DeviceCalculatedState(UsageUpdate(address, 0))
    )
    UsageUpdate(
      address,
      deviceCalculatedState.usages.usage + usage
    )
  }

  def combineUpdateUsage(
    signedUpdate: Signed[UsageUpdate],
    acc         : DataState[UsageUpdateState, UsageUpdateCalculatedState]
  ): DataState[UsageUpdateState, UsageUpdateCalculatedState] = {
    val update = signedUpdate.value
    val address = update.address

    val updatedDeviceUsage = getUpdatedDeviceUsage(update.usage, acc, address)
    val device = DeviceCalculatedState(updatedDeviceUsage)
    val devices = acc.calculated.devices.updated(address, device)

    val updates: List[UsageUpdate] = update :: acc.onChain.updates

    DataState(
      UsageUpdateState(updates),
      UsageUpdateCalculatedState(devices)
    )
  }
}