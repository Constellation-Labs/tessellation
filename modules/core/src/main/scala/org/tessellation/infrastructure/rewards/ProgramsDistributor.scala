package org.tessellation.infrastructure.rewards

import cats.data.StateT
import cats.syntax.foldable._

import org.tessellation.config.types.ProgramsDistributionConfig
import org.tessellation.ext.refined._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

trait ProgramsDistributor[F[_]] {

  def distribute(): DistributionState[F]

}

object ProgramsDistributor {

  def make(config: ProgramsDistributionConfig): ProgramsDistributor[Either[ArithmeticException, *]] =
    () =>
      StateT { amount =>
        def calculateRewards(denominator: NonNegLong): Either[ArithmeticException, List[(Address, NonNegLong)]] =
          config.weights.toList.foldM(List.empty[(Address, NonNegLong)]) { (acc, item) =>
            (acc, item) match {
              case (rewards, (address, weight)) =>
                for {
                  numerator <- amount.coerce * weight.coerce
                  reward <- numerator / denominator
                } yield (address -> reward) :: rewards
            }
          }

        for {
          weightSum <- config.weights.toList.map(_._2.coerce).sumAll
          denominator <- weightSum + config.remainingWeight.coerce

          rewards <- calculateRewards(denominator)
          rewardsSum <- rewards.map(_._2).sumAll

          remainingAmount <- amount.coerce - rewardsSum

          result = rewards.map { case (address, amountValue) => (address, Amount(amountValue)) }
        } yield (Amount(remainingAmount), result)
      }
}