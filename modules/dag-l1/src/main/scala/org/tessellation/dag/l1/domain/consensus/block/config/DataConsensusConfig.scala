package org.tessellation.dag.l1.domain.consensus.block.config

import scala.concurrent.duration.FiniteDuration

import eu.timepit.refined.types.numeric.PosInt

case class DataConsensusConfig(peersCount: PosInt, timeout: FiniteDuration, maxDataUpdatesToDequeue: PosInt)
