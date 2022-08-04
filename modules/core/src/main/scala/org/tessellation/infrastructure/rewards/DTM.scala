package org.tessellation.infrastructure.rewards

import cats.data.StateT

import org.tessellation.config.types.DTMConfig
import org.tessellation.ext.refined._
import org.tessellation.schema.balance.Amount

object DTM {

  def make(config: DTMConfig): RewardsDistributor[Either[ArithmeticException, *]] =
    (_, _) =>
      StateT { amount: Amount =>
        for {
          numerator <- amount.value * config.dtmWeight
          denominator <- config.dtmWeight + config.remainingWeight
          dtmRewards <- numerator / denominator
          remainingRewards <- amount.value - dtmRewards
        } yield (Amount(remainingRewards), List(config.address -> Amount(dtmRewards)))
      }
}
