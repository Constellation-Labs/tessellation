package io.constellationnetwork.dag.l1.domain.transaction

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.TransactionFee

case class TransactionLimitConfig(
  baseBalance: Balance,
  timeToWaitForBaseBalance: FiniteDuration,
  minFeeWithoutLimit: TransactionFee,
  timeTriggerInterval: FiniteDuration
)
