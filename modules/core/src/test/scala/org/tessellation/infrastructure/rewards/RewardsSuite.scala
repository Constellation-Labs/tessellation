package org.tessellation.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.list._

import scala.collection.immutable.SortedSet

import org.tessellation.config.types._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema._
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.generators.{chooseNumRefined, signatureGen, signedTransactionGen}
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
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

  val epochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, NonNegLong.MaxValue).map(EpochProgress(_))

  val meaningfulEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, upperBound, special: _*).map(EpochProgress(_))

  val overflowEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBoundNoMinting, NonNegLong.MaxValue).map(EpochProgress(_))

  def signatureProofsGen(implicit genIdFn: GenIdFn): Gen[NonEmptySet[SignatureProof]] = for {
    signature <- signatureGen
    id <- Gen.delay(genIdFn())
    proofs <- Gen.nonEmptyListOf(SignatureProof(id, signature))
  } yield proofs.toNel.get.toNes

  def snapshotWithoutTransactionsGen(implicit genIdFn: GenIdFn, ks: KryoSerializer[IO]): Gen[Signed[GlobalIncrementalSnapshot]] = for {
    proofs <- signatureProofsGen
    epochProgress <- epochProgressGen
    snapshot = Signed(GlobalSnapshot.mkGenesis(Map.empty, epochProgress), proofs)
    incremental = Signed(GlobalIncrementalSnapshot.fromGlobalSnapshot(snapshot).unsafeRunSync(), proofs)
  } yield incremental

  def makeRewards(config: RewardsConfig)(
    implicit sp: SecurityProvider[IO]
  ): Rewards[F, DAGTransaction, DAGBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot] = {
    val programsDistributor = ProgramsDistributor.make(config.programs)
    val regularDistributor = FacilitatorDistributor.make
    Rewards.make[IO](config.rewardsPerEpoch, programsDistributor, regularDistributor)
  }

  def getAmountByEpoch(epochProgress: EpochProgress): Amount =
    config.rewardsPerEpoch
      .minAfter(epochProgress)
      .map { case (_, reward) => reward }
      .getOrElse(Amount.empty)

  test("event trigger reward transactions sum up to the total fee") { res =>
    implicit val (ks, sp, makeIdFn) = res

    val gen = for {
      snapshot <- snapshotWithoutTransactionsGen
      txs <- Gen.listOf(signedTransactionGen).map(_.toSortedSet)
    } yield (snapshot, txs)

    forall(gen) {
      case (snapshot, txs) =>
        for {
          rewards <- makeRewards(config).pure[F]
          expectedSum = txs.toList.map(_.fee.value.toLong).sum
          rewardTxs <- rewards.distribute(snapshot, txs, EventTrigger)
          sum = rewardTxs.toList.map(_.amount.value.toLong).sum
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

  test("time trigger minted reward transactions sum up to the total snapshot reward for epoch") { res =>
    implicit val (ks, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- meaningfulEpochProgressGen
      snapshot <- snapshotWithoutTransactionsGen
    } yield (epochProgress, snapshot.focus(_.value.epochProgress).replace(epochProgress))

    forall(gen) {
      case (epochProgress, snapshot) =>
        for {
          rewards <- makeRewards(config).pure[F]
          txs <- rewards.distribute(snapshot, SortedSet.empty, TimeTrigger)
          sum = txs.toList.map(_.amount.value.toLong).sum
          expected = getAmountByEpoch(epochProgress).value.toLong
        } yield expect(sum == expected)
    }
  }

  test("time trigger reward transactions include fees from transactions") { res =>
    implicit val (ks, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- meaningfulEpochProgressGen
      snapshot <- snapshotWithoutTransactionsGen
      txs <- Gen.listOf(signedTransactionGen).map(_.toSortedSet)
    } yield (epochProgress, snapshot.focus(_.value.epochProgress).replace(epochProgress), txs)

    forall(gen) {
      case (epochProgress, lastSnapshot, txs) =>
        for {
          rewards <- makeRewards(config).pure[F]
          rewardTransactions <- rewards.distribute(lastSnapshot, txs, TimeTrigger)
          rewardsSum = rewardTransactions.toList.map(_.amount.value.toLong).sum
          expectedMintedSum = getAmountByEpoch(epochProgress).value.toLong
          expectedFeeSum = txs.toList.map(_.fee.value.toLong).sum
        } yield expect(rewardsSum == expectedMintedSum + expectedFeeSum)
    }
  }

  test("time trigger reward transactions won't be generated after the last epoch") { res =>
    implicit val (ks, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- overflowEpochProgressGen
      snapshot <- snapshotWithoutTransactionsGen
    } yield snapshot.focus(_.value.epochProgress).replace(epochProgress)

    forall(gen) { snapshot =>
      for {
        rewards <- makeRewards(config).pure[F]
        txs <- rewards.distribute(snapshot, SortedSet.empty, TimeTrigger)
      } yield expect(txs.isEmpty)
    }
  }
}
