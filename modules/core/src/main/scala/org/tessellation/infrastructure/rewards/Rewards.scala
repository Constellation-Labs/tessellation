package org.tessellation.infrastructure.rewards

import cats.Applicative
import cats.arrow.FunctionK.lift
import cats.data._
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import org.tessellation.config.types.RewardsConfig
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.domain.rewards._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.transaction.{RewardTransaction, TransactionAmount}
import org.tessellation.security.SecurityProvider
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.estatico.newtype.ops.toCoercibleIdOps

object Rewards {

  def make[F[_]: Async: SecurityProvider](
    config: RewardsConfig,
    softStaking: SimpleRewardsDistributor,
    dtm: SimpleRewardsDistributor,
    stardust: SimpleRewardsDistributor,
    regular: RewardsDistributor[F]
  ): Rewards[F] =
    new Rewards[F] {

//      private val logger = Slf4jLogger.getLogger[F]

      def calculateRewards(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[Id]
      ): F[SortedSet[RewardTransaction]] = {
        val amount = getAmountByEpoch(epochProgress)

        val programRewardsState = for {
          dtmRewards <- dtm.distribute(epochProgress, facilitators)
          softStakingRewards <- softStaking.distribute(epochProgress, facilitators)
          stardustCollective <- stardust.distribute(epochProgress, facilitators)
        } yield dtmRewards ++ softStakingRewards ++ stardustCollective

        def eitherToF[A](either: Either[ArithmeticException, A]): F[A] = either.liftTo[F]

        val allRewardsState = for {
          programRewards <- programRewardsState.mapK[F](lift(eitherToF))
          regularRewards <- regular.distribute(epochProgress, facilitators)
        } yield programRewards ++ regularRewards

        allRewardsState
          .run(amount)
          .flatTap {
            case (remaining, _) =>
              Applicative[F].whenA(remaining =!= Amount(0L))(
                Applicative[F].unit
//                logger.error(s"Some rewards were not distributed {amount=${amount.show}, remainingAmount=${remaining.show}}")
              )
          }
          .map {
            case (_, rewards) =>
              rewards.flatMap {
                case (address, amount) =>
                  refineV[Positive](amount.coerce.value).toList.map(a => RewardTransaction(address, TransactionAmount(a)))
              }.toSortedSet
          }
      }

      def getAmountByEpoch(epochProgress: EpochProgress): Amount =
        config.rewardsPerEpoch
          .minAfter(epochProgress)
          .map { case (_, reward) => reward }
          .getOrElse(Amount.empty)
    }

}
