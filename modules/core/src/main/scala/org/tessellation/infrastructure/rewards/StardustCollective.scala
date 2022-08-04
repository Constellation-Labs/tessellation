package org.tessellation.infrastructure.rewards

import cats.data.StateT

import org.tessellation.config.types.StardustConfig
import org.tessellation.ext.refined._
import org.tessellation.schema.balance.Amount

object StardustCollective {

  def make(config: StardustConfig): RewardsDistributor[Either[ArithmeticException, *]] =
    (_, _) =>
      StateT { amount =>
        for {
          numerator <- amount.value * config.stardustWeight
          denominator <- config.stardustWeight + config.remainingWeight
          stardustRewards <- numerator / denominator
          remainingRewards <- amount.value - stardustRewards
        } yield (Amount(remainingRewards), List(config.address -> Amount(stardustRewards)))
      }
}
