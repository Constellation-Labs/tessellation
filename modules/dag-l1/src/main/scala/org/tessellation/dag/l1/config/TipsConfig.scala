package org.tessellation.dag.l1.config

import eu.timepit.refined.types.numeric.PosInt

case class TipsConfig(minimumTipsCount: PosInt, maximumTipsCount: PosInt, maximumTipUsages: PosInt)
