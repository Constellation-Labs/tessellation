package org.tessellation.dag.l1.domain.consensus.block.config

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

case class ConsensusConfig(peersCount: PosInt = 2, tipsCount: PosInt = 2)
