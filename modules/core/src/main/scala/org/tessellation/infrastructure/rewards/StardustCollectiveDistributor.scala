package org.tessellation.infrastructure.rewards

import cats.data.StateT

import org.tessellation.config.types.StardustConfig
import org.tessellation.ext.refined._
import org.tessellation.schema.balance.Amount

trait StardustCollectiveDistributor[F[_]] {
  def distribute(): DistributionState[F]
}

object StardustCollectiveDistributor {

  def make(config: StardustConfig): StardustCollectiveDistributor[Either[ArithmeticException, *]] =
    () =>
      StateT { amount =>
        for {
          numerator <- amount.value * config.stardustWeight
          denominator <- config.stardustWeight + config.remainingWeight
          stardustRewards <- numerator / denominator
          remainingRewards <- amount.value - stardustRewards
        } yield (Amount(remainingRewards), List(config.address -> Amount(stardustRewards)))
      }
}
