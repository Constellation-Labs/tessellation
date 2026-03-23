package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.schema._

import eu.timepit.refined.types.numeric.NonNegLong

trait TipUsageManager[F[_]] {
  def getTipsUsages(
    lastActive: Set[ActiveTip],
    lastDeprecated: Set[DeprecatedTip]
  ): Map[BlockReference, NonNegLong]
}

object TipUsageManager {

  def make[F[_]](): TipUsageManager[F] = new TipUsageManager[F] {

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }
  }
}
