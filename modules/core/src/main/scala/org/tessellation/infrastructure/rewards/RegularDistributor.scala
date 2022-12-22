package org.tessellation.infrastructure.rewards

import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.ext.refined._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.security.SecurityProvider

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

trait RegularDistributor[F[_]] {
  def distribute(random: Random[F], facilitators: NonEmptySet[Id]): DistributionState[F]
}

object RegularDistributor {

  def make[F[_]: Async: SecurityProvider]: RegularDistributor[F] =
    (random, facilitators) =>
      StateT { amount =>
        facilitators.toList
          .traverse(_.toAddress)
          .flatMap(random.shuffleList)
          .map { addresses =>
            for {
              (bottomAmount, reminder) <- amount.value /% NonNegLong.unsafeFrom(addresses.length.toLong)
              topAmount <- bottomAmount + 1L

              (topRewards, bottomRewards) = addresses
                .splitAt(reminder.toInt)
                .bimap(_.map(_ -> Amount(topAmount)), _.map(_ -> Amount(bottomAmount)))

              allRewards = topRewards ++ bottomRewards

              rewardsSum <- allRewards.foldM(NonNegLong.MinValue) { case (acc, (_, amount)) => acc + amount.coerce }
              remainingRewards <- amount.coerce - rewardsSum
            } yield (Amount(remainingRewards), allRewards)
          }
          .map(_.liftTo[F])
          .flatten
      }

}
