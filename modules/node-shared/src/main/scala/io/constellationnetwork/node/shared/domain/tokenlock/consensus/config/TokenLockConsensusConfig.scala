package io.constellationnetwork.node.shared.domain.tokenlock.consensus.config

import scala.concurrent.duration.FiniteDuration

import eu.timepit.refined.types.numeric.PosInt

case class TokenLockConsensusConfig(peersCount: PosInt, timeout: FiniteDuration, maxTokenLocksToDequeue: PosInt)
