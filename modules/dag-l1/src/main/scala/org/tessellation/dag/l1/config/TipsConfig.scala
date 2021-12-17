package org.tessellation.dag.l1.config

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

case class TipsConfig(minimumTipsCount: PosInt = 2, maximumTipsCount: PosInt = 10, maximumTipUsages: PosInt = 2)
