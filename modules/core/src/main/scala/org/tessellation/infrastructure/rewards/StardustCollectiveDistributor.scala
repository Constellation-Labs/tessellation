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
          numeratorPrimary <- amount.value * config.primaryWeight
          numeratorSecondary <- amount.value * config.secondaryWeight
          denominator <- (config.primaryWeight + config.secondaryWeight).flatMap(_ + config.remainingWeight)
          primaryRewards <- numeratorPrimary / denominator
          secondaryRewards <- numeratorSecondary / denominator
          remainingRewards <- (amount.value - primaryRewards).flatMap(_ - secondaryRewards)
        } yield
          (
            Amount(remainingRewards),
            List(
              config.addressPrimary -> Amount(primaryRewards),
              config.addressSecondary -> Amount(secondaryRewards)
            )
          )
      }
}
