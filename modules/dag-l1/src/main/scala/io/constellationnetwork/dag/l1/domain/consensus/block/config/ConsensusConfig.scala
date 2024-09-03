package io.constellationnetwork.dag.l1.domain.consensus.block.config

import scala.concurrent.duration.FiniteDuration

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}

case class ConsensusConfig(peersCount: PosInt, tipsCount: PosInt, timeout: FiniteDuration, pullTxsCount: NonNegLong)
