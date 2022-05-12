package org.tessellation.dag.block

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

package object processing {

  // TODO replace NonNegLong with @newtype UsageCount and create `Next[UsageCount]` instead of the `usageIncrement`
  val usageIncrement: NonNegLong = 1L
  val initUsageCount: NonNegLong = 0L
  val deprecationThreshold: NonNegLong = 2L

}
