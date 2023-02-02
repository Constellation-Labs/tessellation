package org.tessellation.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.list._

import scala.collection.immutable.SortedSet

import org.tessellation.config.types._
import org.tessellation.domain.rewards.Rewards
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.generators.{chooseNumRefined, transactionGen}
import org.tessellation.schema.transaction.TransactionFee
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object RewardsSuite extends MutableIOSuite with Checkers {
  type GenIdFn = () => Id
  type Res = (KryoSerializer[IO], SecurityProvider[IO], GenIdFn)

  override def sharedResource: Resource[IO, Res] = for {
    kryo <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(sdkKryoRegistrar))
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    mkKeyPair = () => KeyPairGenerator.makeKeyPair.map(_.getPublic.toId).unsafeRunSync()
  } yield (kryo, sp, mkKeyPair)

  val config: RewardsConfig = RewardsConfig()
  val totalSupply: Amount = Amount(1599999999_74784000L) // approx because of rounding
  val expectedWeightsSum: Weight = Weight(100L)

  val lowerBound: NonNegLong = EpochProgress.MinValue.value
  val upperBound: NonNegLong = config.rewardsPerEpoch.keySet.max.value
  val lowerBoundNoMinting: NonNegLong = NonNegLong.unsafeFrom(upperBound.value + 1)
  val special: Seq[NonNegLong] =
    config.rewardsPerEpoch.keys.flatMap(epochEnd => Seq(epochEnd.value, NonNegLong.unsafeFrom(epochEnd.value + 1L))).toSeq

  val snapshotOrdinalGen: Gen[SnapshotOrdinal] =
    chooseNumRefined(SnapshotOrdinal.MinValue.value, NonNegLong.MinValue, special: _*).map(SnapshotOrdinal(_))

  val meaningfulEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, upperBound, special: _*).map(EpochProgress(_))

  val overflowEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBoundNoMinting, NonNegLong.MaxValue).map(EpochProgress(_))

  def facilitatorsGen(implicit genIdFn: GenIdFn): Gen[NonEmptySet[Id]] = Gen
    .nonEmptyListOf(
      Gen.delay(genIdFn())
    )
    .map(_.toNel.get.toNes)

  def makeRewards(config: RewardsConfig)(
    implicit sp: SecurityProvider[IO]
  ): Rewards[F] = {
    val programsDistributor = ProgramsDistributor.make(config.programs)
    val regularDistributor = FacilitatorDistributor.make
    Rewards.make[IO](config.rewardsPerEpoch, programsDistributor, regularDistributor)
  }

  test("fee rewards sum up to the total fee") { res =>
    implicit val (_, sp, makeIdFn) = res

    // total supply is lower than Long.MaxValue so generated fee needs to be limited to avoid cases which won't happen
    val feeMaxVal = TransactionFee(NonNegLong(99999999_00000000L))

    val gen = for {
      snapshotOrdinal <- snapshotOrdinalGen
      facilitators <- facilitatorsGen
      txs <- Gen.nonEmptyListOf(transactionGen.retryUntil(_.fee.value < feeMaxVal.value))
    } yield (snapshotOrdinal, facilitators, SortedSet.from(txs))

    forall(gen) {
      case (epochProgress, facilitators, txs) =>
        for {
          rewards <- makeRewards(config).pure[F]
          expectedSum = txs.toList.map(_.fee.value.toLong).sum
          txs <- rewards.feeDistribution(epochProgress, txs, facilitators)
          sum = txs.toList.map(_.amount.value.toLong).sum
        } yield expect(sum === expectedSum)
    }
  }

  pureTest("all the epochs sum up to the total supply") {
    val l = config.rewardsPerEpoch.toList
      .prepended((EpochProgress(0L), Amount(0L)))

    val sum = l.zip(l.tail).foldLeft(0L) {
      case (acc, ((pP, _), (cP, cA))) => acc + (cA.value * (cP.value - pP.value))
    }

    expect(Amount(NonNegLong.unsafeFrom(sum)) === totalSupply)
  }

  pureTest("all program weights sum up to the expected value") {
    val weightSum = config.programs.weights.toList.map(_._2.value.value).sum +
      config.programs.remainingWeight.value

    expect.eql(expectedWeightsSum.value.value, weightSum)
  }

  test("generated reward transactions sum up to the total snapshot reward") { res =>
    implicit val (_, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- meaningfulEpochProgressGen
      facilitators <- facilitatorsGen
    } yield (epochProgress, facilitators)

    forall(gen) {
      case (epochProgress, facilitators) =>
        for {
          rewards <- makeRewards(config).pure[F]
          txs <- rewards.mintedDistribution(epochProgress, facilitators)
          sum = txs.toList.map(_.amount.value.toLong).sum
          expected = rewards.getAmountByEpoch(epochProgress, config.rewardsPerEpoch).value.toLong

        } yield expect(sum == expected)
    }
  }

  test("reward transactions won't be generated after the last epoch") { res =>
    implicit val (_, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- overflowEpochProgressGen
      facilitators <- facilitatorsGen
    } yield (epochProgress, facilitators)

    forall(gen) {
      case (epochProgress, facilitators) =>
        for {
          rewards <- makeRewards(config).pure[F]
          txs <- rewards.mintedDistribution(epochProgress, facilitators)
        } yield expect(txs.isEmpty)
    }
  }
}
