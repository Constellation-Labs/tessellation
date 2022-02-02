package org.tessellation.dag.l1.domain.consensus.block.config

import eu.timepit.refined.types.numeric.PosInt

case class ConsensusConfig(peersCount: PosInt, tipsCount: PosInt)
