package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.infrastructure.rewards.GlobalDelegatedRewardsDistributor
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, AmountOverflow, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.MutableIOSuite

/** Lives in dagL0 so the snapshot settlement seams can be tested with the real reward distributor. */
object GlobalSnapshotDelegatedStakeSettlementSuite extends MutableIOSuite {
  type Res = Hasher[IO]

  def sharedResource: Resource[IO, Res] = Resource.eval(JsonSerializer.forSync[IO]).map { implicit serializer =>
    Hasher.forJson[IO]
  }

  private val owner = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val other = Address("DAG8hGZnBCZiiFTJwYr4BnZtHQcFEUbxo1jxmK3r")
  private val third = Address("DAG5bvqxSJmbWVwcKWEU7nb3sgTnN1QZMPi4F8Cc")
  private val node = Id(Hex("1234567890abcdef")).toPeerId
  private val proof = NonEmptySet.one(SignatureProof(node.toId, Signature(Hex(Hash.empty.value))))
  private val activation = SnapshotOrdinal.unsafeApply(10L)
  private val epoch = EpochProgress(NonNegLong.unsafeFrom(10L))
  private val initialBalances = SortedMap(owner -> balance(7L), other -> balance(11L), third -> balance(13L))
  private val noEvents = UpdateDelegatedStakeAcceptanceResult(SortedMap.empty, List.empty, SortedMap.empty, List.empty)

  private def amount(value: Long): Amount = Amount(NonNegLong.unsafeFrom(value))
  private def balance(value: Long): Balance = Balance(NonNegLong.unsafeFrom(value))
  private def reward(address: Address, value: Long): RewardTransaction =
    RewardTransaction(address, TransactionAmount(PosLong.unsafeFrom(value)))

  private def tokenLock(value: Long = 1000L, unlockEpoch: Option[EpochProgress] = None): Signed[TokenLock] = Signed(
    TokenLock(
      owner,
      TokenLockAmount(PosLong.unsafeFrom(value)),
      TokenLockFee(NonNegLong.MinValue),
      TokenLockReference.empty,
      None,
      unlockEpoch
    ),
    proof
  )

  private def withdrawal(ref: Hash, rewards: Long, created: Long = 1L, source: Address = owner): PendingDelegatedStakeWithdrawal =
    PendingDelegatedStakeWithdrawal(
      Signed(UpdateDelegatedStake.Create(source, node, DelegatedStakeAmount(NonNegLong.unsafeFrom(1000L)), tokenLockRef = ref), proof),
      amount(rewards),
      SnapshotOrdinal.unsafeApply(created),
      EpochProgress(NonNegLong.unsafeFrom(created))
    )

  private case class Result(
    settlement: Option[DelegatedStakeWithdrawalSettlement],
    rewards: DelegatedRewardsResult,
    transition: GlobalSnapshotAcceptanceManager.DelegatedStakeTokenLockTransition
  )

  private def settle(
    expired: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    locks: Map[Hash, Signed[TokenLock]],
    ordinal: SnapshotOrdinal = activation,
    balances: SortedMap[Address, Balance] = initialBalances,
    unexpired: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]] = SortedMap.empty
  )(implicit hasher: Hasher[IO]): IO[Result] =
    for {
      settlement <- IO.fromEither(DelegatedStakeWithdrawalSettlement.prepare(expired, unexpired, locks, ordinal, activation))
      partitioned = PartitionedStakeUpdates(SortedMap.empty, unexpired, expired, settlement)
      rewards <- GlobalDelegatedRewardsDistributor
        .make[IO](AppEnvironment.Dev, DefaultDelegatedRewardsConfigProvider.getConfig())
        .distribute(
          GlobalSnapshotInfo.empty,
          EventTrigger,
          epoch,
          List.empty,
          noEvents,
          partitioned
        )
      (rewardBalances, acceptedRewards) <- IO.fromEither(
        if (settlement.isDefined) GlobalSnapshotAcceptanceManager.acceptRewardTxsChecked(balances, rewards.withdrawalRewardTxs)
        else Right(GlobalSnapshotAcceptanceManager.acceptRewardTxs(balances, rewards.withdrawalRewardTxs))
      )
      _ <- IO.raiseWhen(acceptedRewards != rewards.withdrawalRewardTxs)(new RuntimeException("Reward transaction was not applied"))
      forUnlock = settlement
        .map(_.withdrawals)
        .getOrElse(
          expired.map { case (address, records) => address -> records.filter(w => locks.contains(w.event.tokenLockRef)) }
            .filter(_._2.nonEmpty)
        )
      generated <- IO.fromEither(
        GlobalSnapshotAcceptanceManager
          .generateDelegatedStakeTokenUnlocks(forUnlock, locks, ordinal, activation)
          .leftMap(error => new RuntimeException(error.toString))
      )
      transition <- GlobalSnapshotAcceptanceManager
        .applyDelegatedStakeTokenLockTransition[IO](
          epoch,
          ordinal,
          activation,
          SnapshotOrdinal.MinValue,
          rewardBalances,
          SortedMap.empty,
          SortedMap.from(locks.values.toList.groupBy(_.source).view.mapValues(SortedSet.from(_))),
          locks,
          generated,
          expired,
          rewards.updatedWithdrawDelegatedStakes
        )
        .flatMap(IO.fromEither(_))
    } yield Result(settlement, rewards, transition)

  test("two buckets sharing one lock preserve legacy payouts at A-1 and settle once to the owner at A and A+1") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      low = withdrawal(hashed.hash, 20L)
      high = withdrawal(hashed.hash, 30L, 2L)
      raw = SortedMap(owner -> SortedSet(low), other -> SortedSet(high))
      before <- settle(raw, Map(hashed.hash -> lock), SnapshotOrdinal.unsafeApply(9L))
      results <- List(activation, activation.next).traverse(ordinal => settle(raw, Map(hashed.hash -> lock), ordinal))
      next <- settle(SortedMap.empty, Map.empty, activation.next, results.head.transition.balances)
      expectedUnlock = TokenUnlock(hashed.hash, lock.amount, None, owner)
    } yield
      expect.all(
        before.settlement.isEmpty,
        before.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 20L), reward(other, 30L)),
        before.transition.balances(owner) == balance(1027L),
        before.transition.balances(other) == balance(41L),
        results.forall(_.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 30L))),
        results.forall(_.rewards.totalEmittedRewardsAmount == Amount.empty),
        results.forall(_.transition.balances == initialBalances.updated(owner, balance(1037L))),
        results.forall(_.transition.generatedArtifacts == SortedSet[SharedArtifact](expectedUnlock)),
        results.forall(_.transition.activeTokenLocks.isEmpty),
        results.forall(_.transition.pendingWithdrawals.isEmpty),
        results.forall(_.settlement.exists(s => s.duplicateCount == 1 && s.orphanCount == 0)),
        next.rewards.withdrawalRewardTxs.isEmpty,
        next.transition.generatedArtifacts.isEmpty,
        next.transition.balances == results.head.transition.balances
      )
  }

  test("same-bucket duplicates select the greatest cumulative reward rather than the last record") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      raw = SortedMap(owner -> SortedSet(withdrawal(hashed.hash, 90L), withdrawal(hashed.hash, 10L, 2L)))
      before <- settle(raw, Map(hashed.hash -> lock), SnapshotOrdinal.unsafeApply(9L))
      at <- settle(raw, Map(hashed.hash -> lock))
    } yield
      expect.all(
        before.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 10L)),
        at.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 90L)),
        at.transition.balances(owner) == balance(1097L)
      )
  }

  test("distinct locks for one owner sum rewards after activation and preserve last-wins history") { implicit hasher =>
    val first = tokenLock()
    val second = tokenLock(2000L)
    for {
      h1 <- first.toHashed
      h2 <- second.toHashed
      raw = SortedMap(owner -> SortedSet(withdrawal(h1.hash, 20L), withdrawal(h2.hash, 30L, 2L)))
      locks = Map(h1.hash -> first, h2.hash -> second)
      before <- settle(raw, locks, SnapshotOrdinal.unsafeApply(9L))
      at <- settle(raw, locks)
    } yield
      expect.all(
        before.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 30L)),
        at.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 50L)),
        at.rewards.totalEmittedRewardsAmount == Amount.empty,
        at.transition.balances(owner) == balance(3057L),
        at.transition.generatedArtifacts.size == 2,
        at.settlement.exists(_.duplicateCount == 0)
      )
  }

  test("equal reward ties and malformed signed sources resolve independently of address buckets") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      earlier = withdrawal(hashed.hash, 30L, 1L, other)
      later = withdrawal(hashed.hash, 30L, 2L, third)
      first <- settle(SortedMap(other -> SortedSet(earlier), third -> SortedSet(later)), Map(hashed.hash -> lock))
      swapped <- settle(SortedMap(other -> SortedSet(later), third -> SortedSet(earlier)), Map(hashed.hash -> lock))
    } yield
      expect.all(
        first == swapped,
        first.settlement.exists(_.withdrawals == SortedMap(owner -> SortedSet(earlier))),
        first.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 30L)),
        first.transition.balances == initialBalances.updated(owner, balance(1037L))
      )
  }

  test("orphans pay neither rewards nor principal after activation and every pending copy is removed") { implicit hasher =>
    val orphanRef = Hash("missing-lock")
    val unrelatedRef = Hash("unrelated-lock")
    val raw = SortedMap(other -> SortedSet(withdrawal(orphanRef, 90L)))
    val retained = withdrawal(unrelatedRef, 5L, 9L)
    val unexpired = SortedMap(third -> SortedSet(withdrawal(orphanRef, 100L, 9L), retained))
    for {
      before <- settle(raw, Map.empty, SnapshotOrdinal.unsafeApply(9L))
      at <- settle(raw, Map.empty, unexpired = unexpired)
    } yield
      expect.all(
        before.rewards.withdrawalRewardTxs == SortedSet(reward(other, 90L)),
        at.rewards.withdrawalRewardTxs.isEmpty,
        at.transition.balances == initialBalances,
        at.transition.generatedArtifacts.isEmpty,
        at.transition.pendingWithdrawals == SortedMap(third -> SortedSet(retained)),
        at.settlement.exists(s => s.orphanCount == 2 && s.duplicateCount == 0)
      )
  }

  test("records equal under pending ordering use accepted ordinal as the final deterministic tie-break") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      earlier = withdrawal(hashed.hash, 30L)
      later = earlier.copy(acceptedOrdinal = SnapshotOrdinal.unsafeApply(2L))
      first <- settle(SortedMap(other -> SortedSet(earlier), third -> SortedSet(later)), Map(hashed.hash -> lock))
      swapped <- settle(SortedMap(other -> SortedSet(later), third -> SortedSet(earlier)), Map(hashed.hash -> lock))
    } yield
      expect.all(
        first == swapped,
        first.settlement.exists(_.withdrawals == SortedMap(owner -> SortedSet(earlier))),
        first.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 30L))
      )
  }

  test("settlement removes later-cooldown copies from all original buckets and cannot pay them again") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      raw = SortedMap(owner -> SortedSet(withdrawal(hashed.hash, 20L)), other -> SortedSet(withdrawal(hashed.hash, 30L, 2L)))
      unexpired = SortedMap(third -> SortedSet(withdrawal(hashed.hash, 90L, 9L)))
      before <- settle(raw, Map(hashed.hash -> lock), SnapshotOrdinal.unsafeApply(9L), unexpired = unexpired)
      at <- settle(raw, Map(hashed.hash -> lock), unexpired = unexpired)
      next <- settle(unexpired, Map.empty, activation.next, at.transition.balances)
    } yield
      expect.all(
        before.transition.pendingWithdrawals == unexpired,
        at.transition.pendingWithdrawals.isEmpty,
        at.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 90L)),
        next.rewards.withdrawalRewardTxs.isEmpty,
        next.transition.balances == at.transition.balances,
        next.transition.generatedArtifacts.isEmpty
      )
  }

  test("natural expiry overlap pays one principal and one reward at both expiry boundaries") { implicit hasher =>
    List(9L, 10L).traverse { unlockAt =>
      val lock = tokenLock(unlockEpoch = Some(EpochProgress(NonNegLong.unsafeFrom(unlockAt))))
      for {
        hashed <- lock.toHashed
        raw = SortedMap(other -> SortedSet(withdrawal(hashed.hash, 30L)))
        result <- settle(raw, Map(hashed.hash -> lock))
      } yield
        expect.all(
          result.rewards.withdrawalRewardTxs == SortedSet(reward(owner, 30L)),
          result.transition.balances == initialBalances.updated(owner, balance(1037L)),
          result.transition.activeTokenLocks.isEmpty,
          (result.transition.generatedArtifacts ++ result.transition.naturallyExpiredArtifacts).size == 1,
          result.transition.naturallyExpiredArtifacts.size == (if (unlockAt < 10L) 1 else 0)
        )
    }.map(_.reduce(_ and _))
  }

  test("zero rewards still release principal without constructing a zero-value reward transaction") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      result <- settle(SortedMap(owner -> SortedSet(withdrawal(hashed.hash, 0L))), Map(hashed.hash -> lock))
    } yield
      expect.all(
        result.rewards.withdrawalRewardTxs.isEmpty,
        result.transition.balances(owner) == balance(1007L),
        result.transition.generatedArtifacts.size == 1
      )
  }

  test("reward aggregation checks overflow only after activation and never sums duplicate entitlements") { implicit hasher =>
    val first = tokenLock()
    val second = tokenLock(2000L)
    for {
      h1 <- first.toHashed
      h2 <- second.toHashed
      locks = Map(h1.hash -> first, h2.hash -> second)
      duplicate = SortedMap(
        owner -> SortedSet(withdrawal(h1.hash, Long.MaxValue)),
        other -> SortedSet(withdrawal(h1.hash, Long.MaxValue, 2L))
      )
      distinct = SortedMap(owner -> SortedSet(withdrawal(h1.hash, Long.MaxValue), withdrawal(h2.hash, 1L, 2L)))
      normalized = DelegatedStakeWithdrawalSettlement.prepare(duplicate, SortedMap.empty, locks, activation, activation)
      overflow = DelegatedStakeWithdrawalSettlement.prepare(distinct, SortedMap.empty, locks, activation, activation)
      legacy = DelegatedStakeWithdrawalSettlement.prepare(distinct, SortedMap.empty, locks, SnapshotOrdinal.unsafeApply(9L), activation)
    } yield
      expect.all(
        normalized.exists(_.exists(_.rewardsByAddress == SortedMap(owner -> amount(Long.MaxValue)))),
        overflow == Left(AmountOverflow),
        legacy == Right(None)
      )
  }
  test("checked withdrawal sums reject negative and positive-wrap overflow and accept the exact bound") { implicit hasher =>
    val a = tokenLock()
    val b = tokenLock(2000L)
    val c = tokenLock(3000L)
    for {
      ha <- a.toHashed
      hb <- b.toHashed
      hc <- c.toHashed
      locks = Map(ha.hash -> a, hb.hash -> b, hc.hash -> c)
      atBound = SortedMap(owner -> SortedSet(withdrawal(ha.hash, Long.MaxValue - 1L), withdrawal(hb.hash, 1L, 2L)))
      positiveWrap = SortedMap(
        owner -> SortedSet(withdrawal(ha.hash, Long.MaxValue), withdrawal(hb.hash, Long.MaxValue, 2L), withdrawal(hc.hash, 3L, 3L))
      )
      exact = DelegatedStakeWithdrawalSettlement.prepare(atBound, SortedMap.empty, locks, activation, activation)
      overflow = DelegatedStakeWithdrawalSettlement.prepare(positiveWrap, SortedMap.empty, locks, activation, activation)
    } yield
      expect.all(
        exact.exists(_.exists(_.rewardsByAddress(owner) == amount(Long.MaxValue))),
        overflow == Left(AmountOverflow)
      )
  }

  test("destination balance overflow fails before principal release or pending cleanup") { implicit hasher =>
    val lock = tokenLock()
    for {
      hashed <- lock.toHashed
      result <- settle(
        SortedMap(owner -> SortedSet(withdrawal(hashed.hash, 1L))),
        Map(hashed.hash -> lock),
        balances = initialBalances.updated(owner, balance(Long.MaxValue))
      ).attempt
    } yield expect(result == Left(AmountOverflow))
  }

  test("checked issuance totals reject overflow even when unchecked addition would wrap positive") { _ =>
    val positiveWrap = SortedSet(reward(owner, Long.MaxValue), reward(other, Long.MaxValue), reward(third, 3L))
    for {
      exact <- DelegatedRewardsDistributor
        .sumMintedAmountChecked[IO](SortedSet(reward(owner, Long.MaxValue)), SortedSet.empty, SortedMap.empty)
      overflow <- DelegatedRewardsDistributor.sumMintedAmountChecked[IO](positiveWrap, SortedSet.empty, SortedMap.empty).attempt
    } yield expect.all(exact == amount(Long.MaxValue), overflow == Left(AmountOverflow))
  }

}
