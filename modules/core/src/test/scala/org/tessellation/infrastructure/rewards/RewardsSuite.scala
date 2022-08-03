package org.tessellation.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.contravariantSemigroupal._
import cats.syntax.list._

import org.tessellation.config.types._
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.generators.chooseNumRefined
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops.PublicKeyOps

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object RewardsSuite extends MutableIOSuite with Checkers {
  type GenIdFn = () => Id
  type Res = (KryoSerializer[IO], SecurityProvider[IO], Random[IO], GenIdFn)

  override def sharedResource: Resource[IO, Res] =
    (
      KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sdkKryoRegistrar)),
      SecurityProvider.forAsync[IO],
      Resource.liftK(Random.scalaUtilRandom[IO])
    ).mapN {
      case (ks, sp, r) =>
        implicit val _sp: SecurityProvider[IO] = sp
        (ks, sp, r, () => KeyPairGenerator.makeKeyPair.map(_.getPublic.toId).unsafeRunSync())
    }

  val config = RewardsConfig.default

  val lowerBound: NonNegLong = EpochProgress.MinValue.value
  val upperBound: NonNegLong = config.rewardsPerEpoch.keySet.max.value
  val lowerBoundNoMinting: NonNegLong = NonNegLong.unsafeFrom(upperBound.value + 1)
  val special: Seq[NonNegLong] =
    config.rewardsPerEpoch.keys.flatMap(epochEnd => Seq(epochEnd.value, NonNegLong.unsafeFrom(epochEnd.value + 1L))).toSeq

  val meaningfulEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, upperBound, special: _*).map(EpochProgress(_))

  val overflowEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBoundNoMinting, NonNegLong.MaxValue).map(EpochProgress(_))

  def facilitatorsGen(implicit genIdFn: GenIdFn): Gen[NonEmptySet[Id]] = Gen
    .nonEmptyListOf(
      Gen.delay(genIdFn())
    )
    .map(_.toNel.get.toNes)

  test("generated reward transactions sum up to the total snapshot reward") { res =>
    implicit val (_, sp, rand, makeIdFn) = res

    val softStaking = SoftStaking.make(config.softStaking)
    val dtm = DTM.make(config.dtm)
    val stardust = StardustCollective.make(config.stardust)
    val regular = Regular.make

    val gen = for {
      epochProgress <- meaningfulEpochProgressGen
      facilitators <- facilitatorsGen
    } yield (epochProgress, facilitators)

    forall(gen) {
      case (epochProgress, facilitators) =>
        for {
          rewards <- Rewards.make[IO](config.rewardsPerEpoch, softStaking, dtm, stardust, regular).pure[F]
          txs <- rewards.calculateRewards(epochProgress, facilitators)
          sum = txs.toList.map(_.amount.value.toLong).sum
          expected = rewards.getAmountByEpoch(epochProgress, config.rewardsPerEpoch).value.toLong

        } yield expect(sum == expected)
    }
  }

  test("reward transactions won't be generated after the last epoch") { res =>
    implicit val (_, sp, rand, makeIdFn) = res

    val softStaking = SoftStaking.make(config.softStaking)
    val dtm = DTM.make(config.dtm)
    val stardust = StardustCollective.make(config.stardust)
    val regular = Regular.make

    val gen = for {
      epochProgress <- overflowEpochProgressGen
      facilitators <- facilitatorsGen(makeIdFn)
    } yield (epochProgress, facilitators)

    forall(gen) {
      case (epochProgress, facilitators) =>
        for {
          rewards <- Rewards.make[IO](config.rewardsPerEpoch, softStaking, dtm, stardust, regular).pure[F]
          txs <- rewards.calculateRewards(epochProgress, facilitators)
        } yield expect(txs.isEmpty)
    }
  }
}
