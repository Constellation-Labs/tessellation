package com.my.project_template.shared_data.combiners

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendAction
import io.constellationnetwork.security.signature.Signed

import com.my.project_template.shared_data.types.Types._
import eu.timepit.refined.types.numeric.NonNegLong

object Combiners {
  private def getUpdatedDeviceUsage(
    usage: NonNegLong,
    acc: DataState[UsageUpdateState, UsageUpdateCalculatedState],
    address: Address
  ): UsageUpdateInfo = {
    val deviceCalculatedState = acc.calculated.devices.getOrElse(
      address,
      DeviceCalculatedState(UsageUpdateInfo(address, NonNegLong.MinValue))
    )
    UsageUpdateInfo(
      address,
      NonNegLong.unsafeFrom(deviceCalculatedState.usages.deviceUsage.value + usage.value)
    )
  }

  def combineUpdateUsage(
    signedUpdate: Signed[UsageUpdate],
    acc: DataState[UsageUpdateState, UsageUpdateCalculatedState]
  ): DataState[UsageUpdateState, UsageUpdateCalculatedState] = {
    val update = signedUpdate.value
    val address = update.address

    val updatedDeviceUsage = getUpdatedDeviceUsage(update.usage, acc, address)
    val device = DeviceCalculatedState(updatedDeviceUsage)
    val devices = acc.calculated.devices.updated(address, device)

    val updates: List[UsageUpdate] = update :: acc.onChain.updates

    val updatedSharedArtifacts = update match {
      case UsageUpdateWithSpendTransaction(_, _, spendTransactionA, spendTransactionB) =>
        acc.sharedArtifacts + SpendAction(spendTransactionA, spendTransactionB)
      case _ => acc.sharedArtifacts
    }

    DataState(
      UsageUpdateState(updates),
      UsageUpdateCalculatedState(devices),
      updatedSharedArtifacts
    )
  }
}
