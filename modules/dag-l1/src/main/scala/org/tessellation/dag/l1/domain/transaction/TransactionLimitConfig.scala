package org.tessellation.dag.l1.domain.transaction

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionFee

case class TransactionLimitConfig(
  baseBalance: Balance,
  timeToWaitForBaseBalance: FiniteDuration,
  minFeeWithoutLimit: TransactionFee,
  timeTriggerInterval: FiniteDuration
)
