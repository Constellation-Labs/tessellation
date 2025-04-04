package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.config.types.RewardsConfig._
import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.dag.l0.infrastructure.rewards.RewardsSuite._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
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
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object RewardsRefactorSuite extends MutableIOSuite with Checkers {
  type GenIdFn = () => Id
  type Res = (Hasher[IO], SecurityProvider[IO], GenIdFn)

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar.union(nodeSharedKryoRegistrar))
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    mkKeyPair = () => KeyPairGenerator.makeKeyPair.map(_.getPublic.toId).unsafeRunSync()
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp, mkKeyPair)

  val config: RewardsConfig = RewardsConfig()

  val updatedConfig: RewardsConfig = config.copy(
    programs = _ =>
      ProgramsDistributionConfig(
        weights = Map(
          dataPool -> NonNegFraction.unsafeFrom(42L, 100L),
          integrationNet -> NonNegFraction.unsafeFrom(20L, 100L),
          stardustNewPrimary -> NonNegFraction.unsafeFrom(7L, 100L),
          testnet -> NonNegFraction.unsafeFrom(7L, 100L)
        ),
        validatorsWeight = NonNegFraction.unsafeFrom(24L, 100L),
        delegatorsWeight = NonNegFraction.unsafeFrom(0L, 100L)
      )
  )
  val lowerBound: NonNegLong = EpochProgress.MinValue.value
  val upperBound: NonNegLong = config.rewardsPerEpoch.keySet.max.value

  val snapshotOrdinalGen: Gen[SnapshotOrdinal] =
    chooseNumRefined(SnapshotOrdinal.MinValue.value, NonNegLong.MinValue, special: _*).map(SnapshotOrdinal(_))

  val epochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, NonNegLong.MaxValue).map(EpochProgress(_))

  val singleEpochRewardsConfig: RewardsConfig = updatedConfig.copy(rewardsPerEpoch = SortedMap(EpochProgress.MaxValue -> Amount(100L)))
  val totalSupply: Amount = Amount(1599999999_74784000L)
  val expectedWeightsSum: BigDecimal = BigDecimal(1.0)

  val fourthMintingLogicLowerBound: NonNegLong = 1947530L
  val fourthMintingLogicUpperBound: NonNegLong = upperBound
  val lowerBoundNoMinting: NonNegLong = NonNegLong.unsafeFrom(upperBound.value + 1)

  val meaningfulEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(lowerBound, upperBound).map(EpochProgress(_))

  val fourthMintingLogicEpochProgressGen: Gen[EpochProgress] =
    chooseNumRefined(fourthMintingLogicLowerBound, fourthMintingLogicUpperBound).map(EpochProgress(_))

  def signatureProofsGen(implicit genIdFn: GenIdFn): Gen[NonEmptySet[SignatureProof]] = for {
    signature <- signatureGen
    id <- Gen.delay(genIdFn())
    proofs <- Gen.nonEmptyListOf(SignatureProof(id, signature))
  } yield proofs.toNel.get.toNes

  def snapshotWithoutTransactionsGen(
    withSignatures: Option[NonEmptySet[SignatureProof]] = None
  )(implicit genIdFn: GenIdFn, h: Hasher[IO]): Gen[Signed[GlobalIncrementalSnapshot]] = for {
    epochProgress <- fourthMintingLogicEpochProgressGen
    proofs <- withSignatures.map(Gen.delay(_)).getOrElse(signatureProofsGen)
    snapshot = Signed(GlobalSnapshot.mkGenesis(Map.empty, epochProgress), proofs)
    incremental = Signed(GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](snapshot.value).unsafeRunSync(), proofs)
  } yield incremental

  def makeRewards(config: RewardsConfig)(
    implicit sp: SecurityProvider[IO]
  ): Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] = {
    val programsDistributor = ProgramsDistributor.make
    val regularDistributor = FacilitatorDistributor.make
    Rewards.make[IO](config, programsDistributor, regularDistributor)
  }

  test("all program weights sum up to the expected value") {
    forall(meaningfulEpochProgressGen) { epochProgress =>
      val configForEpoch = updatedConfig.programs(epochProgress)
      val weightSum = configForEpoch.weights.toList.map(_._2.toBigDecimal).sum +
        configForEpoch.validatorsWeight.toBigDecimal

      expect.eql(expectedWeightsSum, weightSum)
    }
  }

  test("minted rewards for the logic at epoch progress 1947530 and later are as expected") { res =>
    implicit val (h, sp, makeIdFn) = res

    val facilitator = makeIdFn.apply()

    val gen = for {
      epochProgress <- fourthMintingLogicEpochProgressGen
      signature <- signatureGen
      signatures = NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(facilitator, signature)))
      snapshot <- snapshotWithoutTransactionsGen(signatures.some)
    } yield snapshot.focus(_.value.epochProgress).replace(epochProgress)

    forall(gen) { snapshot =>
      for {
        oneTimeRewards <- // one-time reward in another epoch should not show up in results
          List(OneTimeReward(snapshot.epochProgress.next, stardustNewPrimary, TransactionAmount(12345L))).pure[F]

        rewards = makeRewards(singleEpochRewardsConfig.copy(oneTimeRewards = oneTimeRewards))

        txs <- rewards.distribute(snapshot, SortedMap.empty, SortedSet.empty, TimeTrigger, Set.empty)

        facilitatorAddress <- facilitator.toAddress
        expected = SortedSet(
          RewardTransaction(stardustNewPrimary, TransactionAmount(7L)),
          RewardTransaction(testnet, TransactionAmount(7L)),
          RewardTransaction(dataPool, TransactionAmount(42L)),
          RewardTransaction(integrationNet, TransactionAmount(20L)),
          RewardTransaction(facilitatorAddress, TransactionAmount(24L))
        )
      } yield expect.eql(expected, txs)
    }
  }
}
