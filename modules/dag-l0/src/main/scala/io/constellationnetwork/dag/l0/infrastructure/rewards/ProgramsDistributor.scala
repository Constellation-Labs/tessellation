package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.StateT
import cats.implicits.catsSyntaxEitherId
import cats.syntax.foldable._

import io.constellationnetwork.dag.l0.config.types.ProgramsDistributionConfig
import io.constellationnetwork.ext.refined._
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.balance.Amount

import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

trait ProgramsDistributor[F[_]] {

  def distribute(config: ProgramsDistributionConfig): DistributionState[F]

}

object ProgramsDistributor {

  def make: ProgramsDistributor[Either[ArithmeticException, *]] =
    config =>
      StateT { amount =>
        def calculateRewards(denominator: NonNegLong): Either[ArithmeticException, List[(Address, NonNegLong)]] =
          config.weights.toList.foldM(List.empty[(Address, NonNegLong)]) { (acc, item) =>
            (acc, item) match {
              case (rewards, (address, weight)) =>
                for {
                  numerator <- amount.coerce * weight.numerator
                  reward <- numerator / denominator
                } yield (address -> reward) :: rewards
            }
          }

        for {
          weightSum <- config.weights.toList.map(_._2.numerator).sumAll
          denominator <- weightSum + config.validatorsWeight.numerator

          rewards <- calculateRewards(denominator)
          rewardsSum <- rewards.map(_._2).sumAll

          remainingAmount <- amount.coerce - rewardsSum

          result = rewards.map { case (address, amountValue) => (address, Amount(amountValue)) }
        } yield (Amount(remainingAmount), result)
      }
}
