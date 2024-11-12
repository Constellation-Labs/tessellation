package io.constellationnetwork.node.shared.domain.consensus.config

import scala.concurrent.duration.FiniteDuration

import eu.timepit.refined.types.numeric.PosInt

case class SwapConsensusConfig(peersCount: PosInt, timeout: FiniteDuration, maxAllowSpendsToDequeue: PosInt)
