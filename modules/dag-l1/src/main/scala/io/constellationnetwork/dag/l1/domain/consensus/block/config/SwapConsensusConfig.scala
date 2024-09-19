package io.constellationnetwork.dag.l1.domain.consensus.block.config

import scala.concurrent.duration.FiniteDuration

import eu.timepit.refined.types.numeric.PosInt

case class SwapConsensusConfig(peersCount: PosInt, timeout: FiniteDuration, maxSwapTransactionsToDequeue: PosInt)
