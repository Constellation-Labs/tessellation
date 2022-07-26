package org.tessellation.infrastructure.rewards

import cats.data.StateT
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.schema.balance.Amount
import org.tessellation.security.SecurityProvider

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

import Arithmetic._

object Regular {

  def make[F[_]: Async: SecurityProvider]: RewardsDistributor[F] =
    (epochProgress, facilitators) =>
      StateT { amount =>
        Random.scalaUtilRandomSeedLong[F](epochProgress.coerce).flatMap { random =>
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

}
