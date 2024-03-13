package org.tessellation.dag.l0.domain.snapshot.programs

import org.tessellation.node.shared.config.types.FeeCalculatorConfig
import org.tessellation.schema.GlobalSnapshotInfo
import org.tessellation.schema.address.Address
import org.tessellation.statechannel.StateChannelSnapshotBinary

trait SnapshotBinaryFeeCalculator {
  def calculateFee(binary: StateChannelSnapshotBinary, info: GlobalSnapshotInfo): Long
}

object SnapshotBinaryFeeCalculator {
  def make(config: FeeCalculatorConfig, getAddress: () => Option[Address] = () => None): SnapshotBinaryFeeCalculator =
    (binary: StateChannelSnapshotBinary, info: GlobalSnapshotInfo) =>
      getAddress()
        .flatMap(info.balances.get)
        .map { balance =>
          val kbyteSize = binary.content.length / 1024.0
          val feePerKb: Long = (binary.fee.value.value / kbyteSize).toLong

          val workAmount: Double = kbyteSize * config.computationalCost.value

          val optionalTip = Math.max(0L, feePerKb - config.baseFee.value)

          val workMultiplier = 1 / (1 + balance.value.value * config.stakingWeight.value)

          val calculatedFee: Double = config.baseFee.value * workAmount * workMultiplier + optionalTip
          calculatedFee.toLong
        }
        .getOrElse(Long.MinValue)
}
