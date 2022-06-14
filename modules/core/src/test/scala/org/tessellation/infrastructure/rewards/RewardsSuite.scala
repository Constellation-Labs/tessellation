package org.tessellation.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.contravariantSemigroupal._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.DurationInt

import org.tessellation.config.types._
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.balance.Amount
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object RewardsSuite extends MutableIOSuite with Checkers {
  type Res = (KryoSerializer[IO], SecurityProvider[IO], Random[IO])

  override def sharedResource: Resource[IO, Res] =
    (
      KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sdkKryoRegistrar)),
      SecurityProvider.forAsync[IO],
      Resource.liftK(Random.scalaUtilRandom[IO])
    ).mapN((_, _, _))

  implicit val order: Ordering[Id] = (x: Id, y: Id) => 1

  val config = RewardsConfig.default

  val totalPool = Amount(160000000000000000L)

  val softStaking = SoftStaking.make(config.softStaking)
  val dtm = DTM.make(config.dtm, 1.minutes)
  val stardust = StardustCollective.make(config.stardust)
  val hash = Hash("")

  val facilitators = NonEmptySet.fromSetUnsafe(
    SortedSet(
      Id(
        Hex(
          "f2b230bb6ca3db890e647c171adbaeea27fc0191b7d84790c020c7fe0e04003c30a62d475af1300d8205a2436d640a1a091b7d53feb5a11bbb000013f75bdcf9"
        )
      ),
      Id(
        Hex(
          "c7f9a08bdea7ff5f51c8af16e223a1d751bac9c541125d9aef5658e9b7597aee8cba374119ebe83fb9edd8c0b4654af273f2d052e2d7dd5c6160b6d6c284a17c"
        )
      )
    )
  )

  test("epoch 1 snapshot reward is valid") { res =>
    implicit val (_, sp, rand) = res

    for {
      rewards <- Rewards.make[IO](config, 1.minutes, softStaking, dtm, stardust).pure[F]
      txs <- rewards
        .calculateRewards(
          EpochProgress(1L),
          facilitators
        )
      sum = txs.toList.map(_.amount.value.toLong).sum

    } yield expect(sum == 65843621389L)
  }

  test("epoch 2 snapshot reward is valid") { res =>
    implicit val (_, sp, rand) = res

    for {
      rewards <- Rewards.make[IO](config, 1.minutes, softStaking, dtm, stardust).pure[F]
      txs <- rewards
        .calculateRewards(
          EpochProgress(1296001L),
          facilitators
        )
      sum = txs.toList.map(_.amount.value.toLong).sum

    } yield expect(sum == 32921810694L)
  }

  test("epoch 3 snapshot reward is valid") { res =>
    implicit val (_, sp, rand) = res

    for {
      rewards <- Rewards.make[IO](config, 1.minutes, softStaking, dtm, stardust).pure[F]
      txs <- rewards
        .calculateRewards(
          EpochProgress(1296001L * 2),
          facilitators
        )
      sum = txs.toList.map(_.amount.value.toLong).sum

    } yield expect(sum == 16460905347L)
  }

  test("epoch 4 snapshot reward is valid") { res =>
    implicit val (_, sp, rand) = res

    for {
      rewards <- Rewards.make[IO](config, 1.minutes, softStaking, dtm, stardust).pure[F]
      txs <- rewards
        .calculateRewards(
          EpochProgress(1296001L * 3),
          facilitators
        )
      sum = txs.toList.map(_.amount.value.toLong).sum

    } yield expect(sum == 8230452674L)
  }

  test("epoch 4+n snapshot reward is 0") { res =>
    implicit val (_, sp, rand) = res

    for {
      rewards <- Rewards.make[IO](config, 1.minutes, softStaking, dtm, stardust).pure[F]
      txs <- rewards
        .calculateRewards(
          EpochProgress(1296001L * 4),
          facilitators
        )
      sum = txs.toList.map(_.amount.value.toLong).sum

    } yield expect(sum == 0)
  }
}
