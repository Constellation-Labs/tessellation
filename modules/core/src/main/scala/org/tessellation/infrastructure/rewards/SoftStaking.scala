package org.tessellation.infrastructure.rewards

import cats.data.StateT
import cats.syntax.all._

import org.tessellation.config.types.SoftStakingAndTestnetConfig
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

import Arithmetic._

object SoftStaking {

  def make(config: SoftStakingAndTestnetConfig): RewardsDistributor[Either[ArithmeticException, *]] =
    (epochProgress, facilitators) =>
      StateT { amount =>
        if (epochProgress < config.startingOrdinal)
          (amount, List.empty).asRight
        else
          for {
            f <- NonNegLong.unsafeFrom(facilitators.length.toLong) * config.facilitatorWeight
            s <- config.softStakeCount * config.softStakeWeight
            t <- config.testnetCount * config.testnetWeight

            numeratorS <- amount.coerce * s
            numeratorT <- amount.coerce * t
            denominator <- (f + s).flatMap(_ + t)

            softStakingRewards <- numeratorS / denominator
            testnetRewards <- numeratorT / denominator
            facilitatorRewards <- (amount.value - softStakingRewards).flatMap(_ - testnetRewards)
          } yield
            (
              Amount(facilitatorRewards),
              List(config.softStakeAddress -> Amount(softStakingRewards), config.testnetAddress -> Amount(testnetRewards))
            )
      }
}
