package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.config.types.OneTimeReward
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.TransactionAmount
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.parser.decode
import io.circe.syntax._
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object NewEraDelegatedRewardsSuite extends SimpleIOSuite with Checkers {
  private val address1 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val address2 = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")
  private val address3 = Address("DAG5bvqxSJmbWVwcKWEU7nb3sgTnN1QZMPi4F8Cc")
  private val node1 = PeerId(Hex("01"))
  private val node2 = PeerId(Hex("02"))
  private val excluded = PeerId(Hex("03"))
  private val activation = SnapshotOrdinal.unsafeApply(100L)
  private val era = DelegatedRewardsEra(activation, SortedMap(activation -> SortedSet(node1, node2)), PosLong(1L))

  private def signed[A](value: A, node: PeerId): Signed[A] =
    Signed(value, NonEmptySet.one(SignatureProof(node.toId, Signature(Hex(Hash.empty.value)))))

  private def stake(address: Address, node: PeerId, value: Long): DelegatedStakeRecord =
    DelegatedStakeRecord(
      signed(
        UpdateDelegatedStake
          .Create(address, node, DelegatedStakeAmount(NonNegLong.unsafeFrom(value)), tokenLockRef = Hash(address.value.value)),
        node
      ),
      SnapshotOrdinal.unsafeApply(1L),
      Balance.empty
    )

  private val stakes = SortedMap(
    address1 -> SortedSet(stake(address1, node1, 100L)),
    address2 -> SortedSet(stake(address2, node2, 300L)),
    address3 -> SortedSet(stake(address3, excluded, 600L))
  )

  private def parameters(address: Address, node: PeerId, commission: Int): (Signed[UpdateNodeParameters], SnapshotOrdinal) =
    signed(
      UpdateNodeParameters(
        address,
        DelegatedStakeRewardParameters(RewardFraction.unsafeFrom(commission)),
        NodeMetadataParameters("", ""),
        UpdateNodeParametersReference.empty
      ),
      node
    ) -> SnapshotOrdinal.unsafeApply(1L)

  private val context = GlobalSnapshotInfo.empty.copy(
    balances = SortedMap(address1 -> Balance(500000L)),
    activeDelegatedStakes = stakes.some,
    updateNodeParameters = SortedMap(
      node1.toId -> parameters(address1, node1, 0),
      node2.toId -> parameters(address2, node2, 100000000),
      excluded.toId -> parameters(address3, excluded, 5000000)
    ).some
  )
  private val emptyDiffs = UpdateDelegatedStakeAcceptanceResult(SortedMap.empty, Nil, SortedMap.empty, Nil)
  private val partitioned = PartitionedStakeUpdates(stakes, SortedMap.empty, SortedMap.empty)

  private def run(
    info: GlobalSnapshotInfo = context,
    signers: List[(Address, PeerId)] = List(address1 -> node1, address3 -> excluded),
    diffs: UpdateDelegatedStakeAcceptanceResult = emptyDiffs,
    updates: PartitionedStakeUpdates = partitioned,
    budget: Long = 10000L
  ): IO[DelegatedRewardsResult] =
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      result <- NewEraDelegatedRewards
        .distribute[IO](info, activation, era, Amount(NonNegLong.unsafeFrom(budget)), signers, diffs, updates, Nil)
    } yield result

  test("all delegations earn fixed net rewards; only eligible signers receive direct rewards and redistributed commission") {
    run().map { result =>
      expect(result.delegatorRewardsMap(node1)(address1).value.value == 315L, "Active signer delegation receives 90% of its gross share")
        .and(expect(result.delegatorRewardsMap(node2)(address2).value.value == 945L, "Offline eligible node delegation still earns"))
        .and(expect(result.delegatorRewardsMap(excluded)(address3).value.value == 1890L, "Excluded node delegation still earns"))
        .and(
          expect(
            result.nodeOperatorRewards.toList.map(r => r.destination -> r.amount.value.value) == List(address1 -> 1350L),
            "Direct rewards and the full commission pool aggregate"
          )
        )
        .and(expect(result.totalEmittedRewardsAmount.value.value == 10000L, "All five allocations conserve the epoch budget"))
        .and(
          expect(
            result.updatedCreateDelegatedStakes.values.flatten.map(_.rewards.value.value).sum == 3150L,
            "Recorded balances equal net delegation issuance"
          )
        )
    }
  }

  test("commission is redistributed proportionally to positive stake among registered eligible signers") {
    run(signers = List(address2 -> node2, address1 -> node1, address3 -> excluded)).map { result =>
      val operators = result.nodeOperatorRewards.map(r => r.destination -> r.amount.value.value).toMap
      expect(operators(address1) == 588L, "100/400 stake gets 88 commission after canonical tie breaking plus 500 direct")
        .and(expect(operators(address2) == 762L, "300/400 stake gets 262 commission plus 500 direct"))
        .and(expect(result.totalEmittedRewardsAmount.value.value == 10000L, "Commission rounding conserves budget"))
    }
  }

  test("missing eligible signers leave direct and commission pools unminted") {
    run(signers = List(address3 -> excluded)).map { result =>
      expect(result.nodeOperatorRewards.isEmpty, "Excluded signers receive neither reward pool")
        .and(expect(result.totalEmittedRewardsAmount.value.value == 8650L, "Only program and net delegation allocations are minted"))
    }
  }

  test("missing registration does not dilute registered signer commission") {
    val info = context.copy(updateNodeParameters = context.updateNodeParameters.map(_ - node2.toId))
    run(info, List(address1 -> node1, address2 -> node2)).map { result =>
      val operators = result.nodeOperatorRewards.map(r => r.destination -> r.amount.value.value).toMap
      expect(operators(address1) == 850L, "Registered signer receives all commission plus half direct rewards")
        .and(expect(operators(address2) == 500L, "Unregistered eligible signer receives only direct rewards"))
    }
  }

  test("no active stake leaves gross delegation pool unminted") {
    run(
      context.copy(activeDelegatedStakes = SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]].some),
      updates = partitioned.copy(unexpiredCreateDelegatedStakes = SortedMap.empty)
    ).map { result =>
      expect(result.delegatorRewardsMap.isEmpty, "No delegation allocation without positive stake")
        .and(expect(result.totalEmittedRewardsAmount.value.value == 6500L, "Programs plus direct validators receive 65 percent"))
    }
  }

  test("accepted withdrawals receive no lost accrual and retain their previous rewards") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      reference <- DelegatedStakeReference.of[IO](stakes(address1).head.event)
      withdrawal = signed(UpdateDelegatedStake.Withdraw(address1, reference.hash), node1)
      result <- run(diffs = emptyDiffs.copy(acceptedWithdrawals = SortedMap(address1 -> List(withdrawal -> EpochProgress(1L)))))
    } yield
      expect(!result.delegatorRewardsMap.contains(node1), "Withdrawn stake receives no new reward")
        .and(expect(!result.updatedCreateDelegatedStakes.contains(address1), "Withdrawal removes the active record"))
        .and(
          expect(
            result.updatedWithdrawDelegatedStakes(address1).head.rewards == Balance.empty,
            "Withdrawal preserves preexisting accrued balance"
          )
        )
        .and(
          expect(
            result.totalEmittedRewardsAmount.value.value == 9650L,
            "Commission is unminted when the only eligible signer no longer has stake"
          )
        )
  }

  test("accepted modifications keep previous rewards but earn no reward in the modification cycle") {
    val original = stakes(address1).head
    val modified = original.event.copy(value = original.event.value.copy(amount = DelegatedStakeAmount(200L)))
    run(diffs = emptyDiffs.copy(acceptedCreates = SortedMap(address1 -> List(modified -> activation)))).map { result =>
      expect(!result.delegatorRewardsMap.contains(node1), "Modified stake is excluded from this cycle").and(
        expect(result.updatedCreateDelegatedStakes(address1).head.event.value.amount.value.value == 200L, "Modified record is applied")
      )
    }
  }

  test("malformed duplicate stakes fail closed before balances can be overcredited") {
    val duplicate =
      stakes(address1).head.copy(event = signed(stakes(address1).head.event.value.copy(tokenLockRef = Hash("another-lock")), node1))
    val malformed = stakes.updated(address1, stakes(address1) + duplicate)
    run(
      context.copy(activeDelegatedStakes = malformed.some),
      updates = partitioned.copy(unexpiredCreateDelegatedStakes = malformed)
    ).attempt.map { result =>
      expect(result == Left(NewEraDelegatedRewards.DuplicateStake), "Duplicate recipient records must not multiply rewards")
    }
  }

  test("multiple expired withdrawals aggregate by destination and do not count as new issuance") {
    val first =
      PendingDelegatedStakeWithdrawal(stakes(address1).head.event, Balance(11L), SnapshotOrdinal.unsafeApply(1L), EpochProgress(1L))
    val second =
      PendingDelegatedStakeWithdrawal(stakes(address2).head.event, Balance(19L), SnapshotOrdinal.unsafeApply(1L), EpochProgress(1L))
    run(updates = partitioned.copy(expiredWithdrawalsDelegatedStaking = SortedMap(address1 -> SortedSet(first, second))), budget = 0L).map {
      result =>
        expect(
          result.withdrawalRewardTxs.toList.map(r => r.destination -> r.amount.value.value) == List(address1 -> 30L),
          "Both withdrawals must reach the wallet"
        ).and(expect(result.totalEmittedRewardsAmount == Amount.empty, "Paying accrued rewards moves supply without creating it"))
    }
  }

  test("repeated signer entries and shuffled inputs cannot alter allocation") {
    for {
      canonical <- run(signers = List(address1 -> node1, address2 -> node2))
      reordered <- run(signers = List(address2 -> node2, address1 -> node1, address2 -> node2))
    } yield expect(canonical == reordered, "Canonical recipient allocation ignores signer input order and duplicate entries")
  }

  test("epoch emission refreshes finalized supply and is active at A and A+1 while A-1 remains exactly legacy") {
    val config = GlobalDelegatedRewardsDistributorSuite.simpleEmissionConfig.copy(eras = Map(AppEnvironment.Dev -> era))
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      distributor = GlobalDelegatedRewardsDistributor.make[IO](AppEnvironment.Dev, config)
      legacy <- distributor.distribute(
        context,
        TimeTrigger,
        EpochProgress(100L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        SnapshotOrdinal.MinValue
      )
      before <- distributor.distribute(
        context,
        TimeTrigger,
        EpochProgress(100L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        SnapshotOrdinal.unsafeApply(99L)
      )
      at <- distributor.distribute(context, TimeTrigger, EpochProgress(100L), List(address1 -> node1), emptyDiffs, partitioned, activation)
      after <- distributor.distribute(
        context.copy(balances = SortedMap(address1 -> Balance(1000000L))),
        TimeTrigger,
        EpochProgress(101L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        SnapshotOrdinal.unsafeApply(101L)
      )
      event <- distributor.distribute(
        context,
        EventTrigger,
        EpochProgress(100L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        activation
      )
    } yield
      expect(before == legacy, "A-1 is byte-for-byte the legacy result")
        .and(
          expect(at.totalEmittedRewardsAmount.value.value == 10000L, "A mints 2 percent of finalized current supply at one epoch per year")
        )
        .and(expect(after.totalEmittedRewardsAmount.value.value == 20000L, "A+1 refreshes the supply base"))
        .and(expect(event.totalEmittedRewardsAmount == Amount.empty, "EventTrigger mints no reward budget"))
  }

  test("integer apportionment conserves every positive budget and is invariant to input order") {
    forall(Gen.zip(Gen.chooseNum(0L, 1000000L), Gen.listOfN(5, Gen.chooseNum(1L, 1000000L)))) {
      case (budget, values) =>
        val weights = values.zipWithIndex.map { case (value, index) => index -> BigInt(value) }
        val allocation = RewardApportionment.allocate(BigInt(budget), weights)
        expect(allocation.map(_._2).sum == BigInt(budget), "Apportionment must conserve its integer budget")
          .and(
            expect(
              allocation == RewardApportionment.allocate(BigInt(budget), weights.reverse),
              "Canonical ties must not depend on input order"
            )
          )
          .and(expect(allocation.forall(_._2 >= 0), "No recipient may receive a negative amount"))
    }
  }

  test("one-time issuance remains explicit, additive and time-triggered at its configured epoch") {
    val config = GlobalDelegatedRewardsDistributorSuite.simpleEmissionConfig.copy(
      eras = Map(AppEnvironment.Dev -> era),
      oneTimeRewards = Map(AppEnvironment.Dev -> List(OneTimeReward(EpochProgress(100L), address1, TransactionAmount(350L))))
    )
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      distributor = GlobalDelegatedRewardsDistributor.make[IO](AppEnvironment.Dev, config)
      timed <- distributor.distribute(
        context,
        TimeTrigger,
        EpochProgress(100L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        activation
      )
      event <- distributor.distribute(
        context,
        EventTrigger,
        EpochProgress(100L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        activation
      )
      next <- distributor.distribute(
        context,
        TimeTrigger,
        EpochProgress(101L),
        List(address1 -> node1),
        emptyDiffs,
        partitioned,
        SnapshotOrdinal.unsafeApply(101L)
      )
    } yield
      expect(timed.totalEmittedRewardsAmount.value.value == 10350L, "One-time issuance is additional to the normal budget")
        .and(
          expect(
            timed.nodeOperatorRewards.toList.map(_.amount.value.value) == List(1700L),
            "One-time payout aggregates with the same destination's direct and commission rewards"
          )
        )
        .and(expect(event.totalEmittedRewardsAmount == Amount.empty, "Event-triggered snapshots never repeat one-time issuance"))
        .and(expect(next.totalEmittedRewardsAmount.value.value == 10000L, "The next epoch does not repeat one-time issuance"))
  }

  test("serialized producer rewards reconstruct the same balances and state proof through the shared replay helpers across activation") {
    val config = GlobalDelegatedRewardsDistributorSuite.simpleEmissionConfig.copy(eras = Map(AppEnvironment.Dev -> era))
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forSync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      distributor = GlobalDelegatedRewardsDistributor.make[IO](AppEnvironment.Dev, config)
      results <- List(SnapshotOrdinal.unsafeApply(99L), activation, SnapshotOrdinal.unsafeApply(101L)).traverse { ordinal =>
        for {
          withdrawalReference <- DelegatedStakeReference.of[IO](stakes(address2).head.event)
          withdrawal = signed(UpdateDelegatedStake.Withdraw(address2, withdrawalReference.hash), node2)
          diffs = emptyDiffs.copy(acceptedWithdrawals = SortedMap(address2 -> List(withdrawal -> EpochProgress(100L))))
          produced <- distributor.distribute(
            context,
            TimeTrigger,
            EpochProgress(100L),
            List(address1 -> node1),
            diffs,
            partitioned,
            ordinal
          )
          encodedRewards = produced.delegatorRewardsMap.asJson.noSpaces
          recordedRewards <- decode[SortedMap[PeerId, Map[Address, Amount]]](encodedRewards).liftTo[IO]
          replayedCreates <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes[IO](recordedRewards, diffs, partitioned)
          replayedWithdrawals <- DelegatedRewardsDistributor.getUpdatedWithdrawalDelegatedStakes[IO](context, diffs, partitioned)
          producerImmediate <-
            if (era.isActive(ordinal))
              RewardTransactionAggregation
                .combine(
                  produced.nodeOperatorRewards.toList ++ produced.reservedAddressRewards.toList ++ produced.withdrawalRewardTxs.toList
                )
                .liftTo[IO]
            else IO.pure(produced.nodeOperatorRewards ++ produced.reservedAddressRewards ++ produced.withdrawalRewardTxs)
          recordedImmediate <- decode[SortedSet[io.constellationnetwork.schema.transaction.RewardTransaction]](
            producerImmediate.asJson.noSpaces
          ).liftTo[IO]
          replayImmediate <-
            if (era.isActive(ordinal)) RewardTransactionAggregation.combine(recordedImmediate.toList).liftTo[IO]
            else IO.pure(recordedImmediate)
          producerBalances <- producerImmediate.toList.foldLeftM(context.balances) { (balances, reward) =>
            balances
              .getOrElse(reward.destination, Balance.empty)
              .plus(Amount(NonNegLong.unsafeFrom(reward.amount.value.value)))
              .liftTo[IO]
              .map(value => balances.updated(reward.destination, value))
          }
          replayBalances <- replayImmediate.toList.foldLeftM(context.balances) { (balances, reward) =>
            balances
              .getOrElse(reward.destination, Balance.empty)
              .plus(Amount(NonNegLong.unsafeFrom(reward.amount.value.value)))
              .liftTo[IO]
              .map(value => balances.updated(reward.destination, value))
          }
          producerInfo = context.copy(
            balances = producerBalances,
            activeDelegatedStakes = produced.updatedCreateDelegatedStakes.some,
            delegatedStakesWithdrawals = produced.updatedWithdrawDelegatedStakes.some
          )
          replayInfo = context.copy(
            balances = replayBalances,
            activeDelegatedStakes = replayedCreates.some,
            delegatedStakesWithdrawals = replayedWithdrawals.some
          )
          producerProof <- producerInfo.stateProof[IO](ordinal)
          replayProof <- replayInfo.stateProof[IO](ordinal)
          producerHash <- hasher.hash(producerInfo)
          replayHash <- hasher.hash(replayInfo)
        } yield
          expect(replayInfo == producerInfo, s"Recorded rewards reproduce all balances and withdrawals at $ordinal")
            .and(expect(replayProof == producerProof, s"State proof must remain identical at $ordinal"))
            .and(expect(replayHash == producerHash, s"Reconstructed state encoding must remain identical at $ordinal"))
      }
    } yield results.reduce(_ and _)
  }
}
