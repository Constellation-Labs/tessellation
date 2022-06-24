package org.tessellation.dag.block

import org.tessellation.dag.transaction.TransactionChainValidator.TransactionNel
import org.tessellation.schema.address.Address

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

package object processing {

  type UsageCount = NonNegLong
  type TxChains = Map[Address, TransactionNel]

  val usageIncrement: NonNegLong = 1L
  val initUsageCount: NonNegLong = 0L
  val deprecationThreshold: NonNegLong = 2L

}
