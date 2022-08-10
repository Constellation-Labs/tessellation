package org.tessellation.infrastructure.rewards

import cats.arrow.FunctionK.lift
import cats.data._
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.domain.rewards.Rewards
import org.tessellation.schema.ID.Id
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.transaction.{RewardTransaction, TransactionAmount}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.estatico.newtype.ops.toCoercibleIdOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Rewards {

  def make[F[_]: Async](
    rewardsPerEpoch: SortedMap[EpochProgress, Amount],
    softStaking: SimpleRewardsDistributor,
    dtm: SimpleRewardsDistributor,
    stardust: SimpleRewardsDistributor,
    regular: RewardsDistributor[F]
  ): Rewards[F] =
    new Rewards[F] {
      private val logger = Slf4jLogger.getLoggerFromClass(Rewards.getClass)

      def calculateRewards(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[Id]
      ): F[SortedSet[RewardTransaction]] = {
        val amount = getAmountByEpoch(epochProgress, rewardsPerEpoch)

        val programRewardsState = for {
          stardustCollective <- stardust.distribute(epochProgress, facilitators)
          softStakingRewards <- softStaking.distribute(epochProgress, facilitators)
          dtmRewards <- dtm.distribute(epochProgress, facilitators)
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
              new RuntimeException(s"Some rewards were not distributed {amount=${amount.show}, remainingAmount=${remaining.show}}")
                .raiseError[F, Unit]
                .whenA(remaining =!= Amount.empty)
          }
          .map {
            case (_, rewards) =>
              rewards.flatMap {
                case (address, amount) =>
                  refineV[Positive](amount.coerce.value).toList.map(a => RewardTransaction(address, TransactionAmount(a)))
              }.toSortedSet
          }
          .flatTap(rewardTxs => logger.info(s"Minted amount of ${amount.show} and distributed in ${rewardTxs.size} transaction(s)"))
      }

      def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount =
        rewardsPerEpoch
          .minAfter(epochProgress)
          .map { case (_, reward) => reward }
          .getOrElse(Amount.empty)
    }
}
