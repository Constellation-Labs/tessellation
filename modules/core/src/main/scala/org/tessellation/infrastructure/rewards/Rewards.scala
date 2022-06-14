package org.tessellation.infrastructure.rewards

import cats.data._
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.semigroup._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.math.Integral.Implicits._

import org.tessellation.config.types.RewardsConfig
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.domain.rewards._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.transaction.{RewardTransaction, TransactionAmount}
import org.tessellation.security.SecurityProvider

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric._
import io.estatico.newtype.ops.toCoercibleIdOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Rewards {

  def make[F[_]: Async: SecurityProvider](
    config: RewardsConfig,
    timeTriggerInterval: FiniteDuration,
    softStaking: SoftStaking,
    dtm: DTM,
    stardust: StardustCollective
  ): Rewards[F] =
    new Rewards[F] {

      implicit val logger = Slf4jLogger.getLogger[F]

      def calculateRewards(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[Id]
      ): F[SortedSet[RewardTransaction]] = {
        val rewards = getRewardsPerSnapshot(epochProgress)

        if (rewards === Amount.empty) {
          SortedSet.empty[RewardTransaction].pure[F]
        } else
          EitherT.liftF {
            facilitators.toNonEmptyList
              .traverse(_.toAddress)
              .map(_.toNes)
          }.flatMap(createSharedDistribution(epochProgress, rewards, _))
            .flatMap { distribution =>
              EitherT.fromEither {
                Kleisli(weightByDTM)
                  .compose(weightByStardust)
                  .apply(distribution)
              }
            }
            .flatMap { distribution =>
              EitherT.fromEither {
                distribution.toNel.traverse {
                  case (address, reward) =>
                    PosLong
                      .from(reward.value)
                      .map(reward => RewardTransaction(address, TransactionAmount(reward)))
                      .left
                      .map[RewardsError](NumberRefinementPredicatedFailure)
                }.map(_.toNes.toSortedSet)
              }
            }
            .flatTap(txs => EitherT.liftF(logger.info(s"Minted reward transactions: $txs")))
            .valueOrF { error =>
              val logError = error match {
                case err @ NumberRefinementPredicatedFailure(_) =>
                  logger.error(err)("Unhandled refinement predicate failure")
                case IgnoredAllAddressesDuringWeighting =>
                  logger.warn("Ignored all the addresses from distribution so weights won't be applied")
              }

              logError.as(SortedSet.empty[RewardTransaction])
            }
      }

      def getRewardsPerSnapshot(epochProgress: EpochProgress): Amount =
        config.rewardsPerEpoch
          .minAfter(epochProgress)
          .map { case (_, reward) => reward }
          .getOrElse(Amount(0L))

      private[rewards] def createSharedDistribution(
        epochProgress: EpochProgress,
        snapshotReward: Amount,
        addresses: NonEmptySet[Address]
      ): EitherT[F, RewardsError, NonEmptyMap[Address, Amount]] = {

        val (quotient, remainder) = snapshotReward.coerce.toLong /% addresses.length.toLong

        val quotientDistribution = addresses.toNonEmptyList.map(_ -> NonNegLong.unsafeFrom(quotient)).toNem

        EitherT.liftF {
          Random.scalaUtilRandomSeedLong(epochProgress.coerce).flatMap { random =>
            random
              .shuffleList(addresses.toNonEmptyList.toList)
              .map { shuffled =>
                shuffled.take(remainder.toInt)
              }
              .map { top =>
                top.toNel
                  .fold(quotientDistribution) { topNel =>
                    topNel.map(_ -> NonNegLong(1L)).toNem |+| quotientDistribution
                  }
                  .mapBoth { case (addr, reward) => (addr, Amount(reward)) }
              }
          }
        }
      }

      private[rewards] def weightBySoftStaking(
        epochProgress: EpochProgress
      ): NonEmptyMap[Address, Amount] => Either[RewardsError, NonEmptyMap[Address, Amount]] =
        epochProgress match {
          case ordinal if ordinal.value >= config.softStaking.startingOrdinal.value =>
            softStaking.weight(config.softStaking.nodes)(Set(stardust.getAddress, dtm.getAddress))
          case _ => distribution => Either.right(distribution)
        }

      private[rewards] def weightByStardust: NonEmptyMap[Address, Amount] => Either[RewardsError, NonEmptyMap[Address, Amount]] =
        stardust.weight(Set.empty)

      private[rewards] def weightByDTM: NonEmptyMap[Address, Amount] => Either[RewardsError, NonEmptyMap[Address, Amount]] =
        dtm.weight(Set(stardust.getAddress))

    }

}
