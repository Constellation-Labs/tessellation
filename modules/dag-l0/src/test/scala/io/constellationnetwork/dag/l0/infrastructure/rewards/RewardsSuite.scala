package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.list._
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.MainnetRewardsConfig._
import io.constellationnetwork.node.shared.config.types.ClassicRewardsConfig
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generators.{chooseNumRefined, signatureGen, signedTransactionGen}
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object RewardsSuite extends MutableIOSuite with Checkers {
  type GenIdFn = () => Id
  type Res = (Hasher[IO], SecurityProvider[IO], GenIdFn)

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    mkKeyPair = () => KeyPairGenerator.makeKeyPair.map(_.getPublic.toId).unsafeRunSync()
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp, mkKeyPair)

  val config: ClassicRewardsConfig = classicMainnetRewardsConfig
  val singleEpochRewardsConfig: ClassicRewardsConfig = config.copy(rewardsPerEpoch = SortedMap(EpochProgress.MaxValue -> Amount(100L)))
  val totalSupply: Amount = Amount(1599999999_74784000L) // approx because of rounding
  val expectedWeightsSum: BigDecimal = BigDecimal(1.0)

  val lowerBound: NonNegLong = EpochProgress.MinValue.value
  val upperBound: NonNegLong = config.rewardsPerEpoch.keySet.max.value

  val secondMintingLogicLowerBound: NonNegLong = 1336392L
  val thirdMintingLogicLowerBound: NonNegLong = 1352274L
  val fourthMintingLogicLowerBound: NonNegLong = 1947530L

  val firstMintingLogicUpperBound: NonNegLong = NonNegLong.unsafeFrom(secondMintingLogicLowerBound.value - 1)
  val secondMintingLogicUpperBound: NonNegLong = NonNegLong.unsafeFrom(thirdMintingLogicLowerBound.value - 1)
  val thirdMintingLogicUpperBound: NonNegLong = NonNegLong.unsafeFrom(fourthMintingLogicLowerBound.value - 1)
  val fourthMintingLogicUpperBound: NonNegLong = upperBound

  val lowerBoundNoMinting: NonNegLong = NonNegLong.unsafeFrom(upperBound.value + 1)
  val special: Seq[NonNegLong] =
    config.rewardsPerEpoch.keys.flatMap(epochEnd => Seq(epochEnd.value, NonNegLong.unsafeFrom(epochEnd.value + 1L))).toSeq

  val snapshotOrdinalGen: Gen[SnapshotOrdinal] =
    chooseNumRefined(SnapshotOrdinal.MinValue.value, NonNegLong.MinValue, special: _*).map(SnapshotOrdinal(_))

  val epochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, NonNegLong.MaxValue).map(EpochProgress(_))

  val firstMintingLogicEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, firstMintingLogicUpperBound).map(EpochProgress(_))

  val secondMintingLogicEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(secondMintingLogicLowerBound, secondMintingLogicUpperBound).map(EpochProgress(_))

  val thirdMintingLogicEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(thirdMintingLogicLowerBound, thirdMintingLogicUpperBound).map(EpochProgress(_))

  val fourthMintingLogicEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(fourthMintingLogicLowerBound, fourthMintingLogicUpperBound).map(EpochProgress(_))

  val meaningfulEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, upperBound, special: _*).map(EpochProgress(_))

  val overflowEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBoundNoMinting, NonNegLong.MaxValue).map(EpochProgress(_))

  def signatureProofsGen(implicit genIdFn: GenIdFn): Gen[NonEmptySet[SignatureProof]] = for {
    signature <- signatureGen
    id <- Gen.delay(genIdFn())
    proofs <- Gen.nonEmptyListOf(SignatureProof(id, signature))
  } yield proofs.toNel.get.toNes

  def snapshotWithoutTransactionsGen(
    withSignatures: Option[NonEmptySet[SignatureProof]] = None
  )(implicit genIdFn: GenIdFn, h: Hasher[IO]): Gen[Signed[GlobalIncrementalSnapshot]] = for {
    epochProgress <- epochProgressGen
    proofs <- withSignatures.map(Gen.delay(_)).getOrElse(signatureProofsGen)
    snapshot = Signed(GlobalSnapshot.mkGenesis(Map.empty, epochProgress), proofs)
    incremental = Signed(GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](snapshot).unsafeRunSync(), proofs)
  } yield incremental

  def makeRewards(config: ClassicRewardsConfig)(
    implicit sp: SecurityProvider[IO]
  ): Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] = {
    val programsDistributor = ProgramsDistributor.make
    val regularDistributor = FacilitatorDistributor.make
    Rewards.make[IO](config, programsDistributor, regularDistributor)
  }

  def getAmountByEpoch(epochProgress: EpochProgress): Amount =
    config.rewardsPerEpoch.toSortedMap
      .minAfter(epochProgress)
      .map { case (_, reward) => reward }
      .getOrElse(Amount.empty)

  test("event trigger reward transactions sum up to 0 indicating fees getting burned") { res =>
    implicit val (h, sp, makeIdFn) = res

    val gen = for {
      snapshot <- snapshotWithoutTransactionsGen()
      txs <- Gen.listOf(signedTransactionGen).map(_.toSortedSet)
    } yield (snapshot, txs)

    forall(gen) {
      case (snapshot, txs) =>
        for {
          rewards <- makeRewards(config).pure[F]
          expectedSum = 0L
          rewardTxs <- rewards.distribute(snapshot, SortedMap.empty, txs, EventTrigger, Set.empty)
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

  test("all program weights sum up to the expected value") {
    forall(meaningfulEpochProgressGen) { epochProgress =>
      val configForEpoch = config.programs(epochProgress)
      val weightSum = configForEpoch.weights.toList.map(_._2.toBigDecimal).sum +
        configForEpoch.validatorsWeight.toBigDecimal +
        configForEpoch.delegatorsWeight.toBigDecimal

      expect.eql(expectedWeightsSum, weightSum)
    }
  }

  test("time trigger minted reward transactions sum up to the total snapshot reward for epoch") { res =>
    implicit val (h, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- meaningfulEpochProgressGen
      snapshot <- snapshotWithoutTransactionsGen()
    } yield (epochProgress, snapshot.focus(_.value.epochProgress).replace(epochProgress))

    forall(gen) {
      case (epochProgress, snapshot) =>
        for {
          rewards <- makeRewards(config).pure[F]
          txs <- rewards.distribute(snapshot, SortedMap.empty, SortedSet.empty, TimeTrigger, Set.empty)
          sum = txs.toList.map(_.amount.value.toLong).sum
          expected = getAmountByEpoch(epochProgress).value.toLong
        } yield expect(sum == expected)
    }
  }

  test("time trigger reward transactions don't include burned fees") { res =>
    implicit val (h, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- meaningfulEpochProgressGen
      snapshot <- snapshotWithoutTransactionsGen()
      txs <- Gen.listOf(signedTransactionGen).map(_.toSortedSet)
    } yield (epochProgress, snapshot.focus(_.value.epochProgress).replace(epochProgress), txs)

    forall(gen) {
      case (epochProgress, lastSnapshot, txs) =>
        for {
          rewards <- makeRewards(config).pure[F]
          rewardTransactions <- rewards.distribute(lastSnapshot, SortedMap.empty, txs, TimeTrigger, Set.empty)
          rewardsSum = rewardTransactions.toList.map(_.amount.value.toLong).sum
          expectedMintedSum = getAmountByEpoch(epochProgress).value.toLong
          expectedFeeSum = 0L
        } yield expect(rewardsSum == expectedMintedSum + expectedFeeSum)
    }
  }

  test("time trigger reward transactions won't be generated after the last epoch") { res =>
    implicit val (h, sp, makeIdFn) = res

    val gen = for {
      epochProgress <- overflowEpochProgressGen
      snapshot <- snapshotWithoutTransactionsGen()
    } yield snapshot.focus(_.value.epochProgress).replace(epochProgress)

    forall(gen) { snapshot =>
      for {
        rewards <- makeRewards(config).pure[F]
        txs <- rewards.distribute(snapshot, SortedMap.empty, SortedSet.empty, TimeTrigger, Set.empty)
      } yield expect(txs.isEmpty)
    }
  }

  test("minted rewards for the logic before epoch progress 1336392 are as expected") { res =>
    implicit val (h, sp, makeIdFn) = res

    val facilitator = makeIdFn.apply()

    val gen = for {
      epochProgress <- firstMintingLogicEpochProgressGen
      signature <- signatureGen
      signatures = NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(facilitator, signature)))
      snapshot <- snapshotWithoutTransactionsGen(signatures.some)
    } yield snapshot.focus(_.value.epochProgress).replace(epochProgress)

    forall(gen) { snapshot =>
      for {
        rewards <- makeRewards(singleEpochRewardsConfig).pure[F]

        txs <- rewards.distribute(snapshot, SortedMap.empty, SortedSet.empty, TimeTrigger, Set.empty)
        facilitatorAddress <- facilitator.toAddress
        expected = SortedSet(
          RewardTransaction(stardustPrimary, TransactionAmount(5L)),
          RewardTransaction(stardustSecondary, TransactionAmount(5L)),
          RewardTransaction(softStaking, TransactionAmount(20L)),
          RewardTransaction(testnet, TransactionAmount(1L)),
          RewardTransaction(dataPool, TransactionAmount(65L)),
          RewardTransaction(facilitatorAddress, TransactionAmount(4L))
        )
      } yield expect.eql(expected, txs)
    }
  }

  test("minted rewards for the logic at epoch progress 1336392 until 1352274 are as expected") { res =>
    implicit val (h, sp, makeIdFn) = res

    val facilitator = makeIdFn.apply()

    val gen = for {
      epochProgress <- secondMintingLogicEpochProgressGen
      signature <- signatureGen
      signatures = NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(facilitator, signature)))
      snapshot <- snapshotWithoutTransactionsGen(signatures.some)
    } yield snapshot.focus(_.value.epochProgress).replace(epochProgress)

    forall(gen) { snapshot =>
      for {
        rewards <- makeRewards(singleEpochRewardsConfig).pure[F]

        txs <- rewards.distribute(snapshot, SortedMap.empty, SortedSet.empty, TimeTrigger, Set.empty)
        facilitatorAddress <- facilitator.toAddress
        expected = SortedSet(
          RewardTransaction(stardustPrimary, TransactionAmount(5L)),
          RewardTransaction(stardustSecondary, TransactionAmount(5L)),
          RewardTransaction(testnet, TransactionAmount(5L)),
          RewardTransaction(dataPool, TransactionAmount(55L)),
          RewardTransaction(facilitatorAddress, TransactionAmount(30L))
        )
      } yield expect.eql(expected, txs)
    }
  }

  test("minted rewards for the logic at epoch progress 1352274 until 1947530 are as expected") { res =>
    implicit val (h, sp, makeIdFn) = res

    val facilitator = makeIdFn.apply()

    val gen = for {
      epochProgress <- thirdMintingLogicEpochProgressGen
      signature <- signatureGen
      signatures = NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(facilitator, signature)))
      snapshot <- snapshotWithoutTransactionsGen(signatures.some)
    } yield snapshot.focus(_.value.epochProgress).replace(epochProgress)

    forall(gen) { snapshot =>
      for {
        rewards <- makeRewards(singleEpochRewardsConfig).pure[F]

        txs <- rewards.distribute(snapshot, SortedMap.empty, SortedSet.empty, TimeTrigger, Set.empty)

        facilitatorAddress <- facilitator.toAddress
        expected = SortedSet(
          RewardTransaction(stardustNewPrimary, TransactionAmount(5L)),
          RewardTransaction(stardustSecondary, TransactionAmount(5L)),
          RewardTransaction(testnet, TransactionAmount(3L)),
          RewardTransaction(dataPool, TransactionAmount(55L)),
          RewardTransaction(integrationNet, TransactionAmount(15L)),
          RewardTransaction(facilitatorAddress, TransactionAmount(17L))
        )
      } yield expect.eql(expected, txs)
    }
  }
}
