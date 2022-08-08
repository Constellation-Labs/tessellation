package org.tessellation.infrastructure.rewards

import cats.data.{NonEmptySet, StateT}
import cats.syntax.either._

import scala.math.Ordered.orderingToOrdered

import org.tessellation.config.types.SoftStakingAndTestnetConfig
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.refined._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

trait SoftStakingDistributor[F[_]] {
  def distribute(epochProgress: EpochProgress, facilitators: NonEmptySet[Id]): DistributionState[F]
}

object SoftStakingDistributor {

  def make(config: SoftStakingAndTestnetConfig): SoftStakingDistributor[Either[ArithmeticException, *]] =
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
