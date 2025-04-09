package io.constellationnetwork.currency.l1.domain.snapshots.programs

import cats.effect._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.Random

import io.constellationnetwork.dag.l1.Main
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.{AddressesConfig, DelegatedStakingConfig, LastGlobalSnapshotsSyncConfig}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security._
import io.constellationnetwork.transaction.TransactionGenerator

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import weaver.SimpleIOSuite

object CurrencySnapshotProcessorSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = CurrencySnapshotAcceptanceManager[IO]

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar).flatMap { implicit kp =>
        for {
          implicit0(jhs: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
          implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
          validators = SharedValidators.make[IO](
            AddressesConfig(Set.empty),
            None,
            None,
            Some(Map.empty),
            SortedMap.empty,
            Long.MaxValue,
            Hasher.forKryo[IO],
            DelegatedStakingConfig(
              RewardFraction(5_000_000),
              RewardFraction(10_000_000),
              PosInt(140),
              Map(Dev -> EpochProgress(NonNegLong(7338977L)))
            )
          )
          currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
            SnapshotOrdinal.MinValue,
            LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(10)),
            BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, Hasher.forKryo[IO]),
            TokenLockBlockAcceptanceManager.make[IO](validators.tokenLockBlockValidator),
            AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
            Amount(0L),
            validators.currencyMessageValidator,
            validators.feeTransactionValidator,
            validators.globalSnapshotSyncValidator
          )
        } yield currencySnapshotAcceptanceManager
      }
    }

  val address1: Address = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU")
  val address2: Address = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebT")
  val address3: Address = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebV")
  val address4: Address = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebX")

  test("should update balances correctly without reward txns") {
    testResources.use { mgr =>
      val base = SortedMap(address1 -> Balance(50L), address2 -> Balance(50L))
      val updates = SortedMap(address1 -> Balance(100L))

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, updates, SortedSet.empty)
      } yield
        expect.all(
          rewardTxs.isEmpty,
          newBalances(address1).value.value == 100L,
          newBalances(address2).value.value == 50L
        )
    }
  }

  test("should apply valid reward transactions") {
    testResources.use { mgr =>
      val base = SortedMap(address1 -> Balance(50L))
      val rewards = SortedSet(RewardTransaction(address1, TransactionAmount(30L)))

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, Map.empty, rewards)
      } yield
        expect.all(
          rewardTxs.size == 1,
          newBalances(address1).value.value == 80L
        )
    }
  }

  test("should skip invalid reward transactions") {
    testResources.use { mgr =>
      val base = SortedMap(address1 -> Balance(Long.MaxValue))
      val rewards = SortedSet(RewardTransaction(address1, TransactionAmount(100L)))

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, Map.empty, rewards)
      } yield
        expect.all(
          rewardTxs.isEmpty,
          newBalances(address1).value.value == Long.MaxValue
        )
    }
  }

  test("should apply rewards and merge with updated balances") {
    testResources.use { mgr =>
      val base = SortedMap(address1 -> Balance(100L))
      val updates = Map(address2 -> Balance(40L))
      val rewards = SortedSet(RewardTransaction(address2, TransactionAmount(10L)))

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, updates, rewards)
      } yield
        expect.all(
          rewardTxs.size == 1,
          newBalances(address1).value.value == 100L,
          newBalances(address2).value.value == 50L
        )
    }
  }

  test("should include new addresses from updates even with no rewards") {
    testResources.use { mgr =>
      val base = SortedMap.empty[Address, Balance]
      val updates = Map(address3 -> Balance(77L))

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, updates, SortedSet.empty)
      } yield
        expect.all(
          rewardTxs.isEmpty,
          newBalances(address3).value.value == 77L
        )
    }
  }

  test("should apply multiple randomized reward transactions") {
    testResources.use { mgr =>
      val base = SortedMap(address1 -> Balance(200L), address2 -> Balance(100L), address3 -> Balance(0L))
      val rewards = SortedSet(
        RewardTransaction(address1, TransactionAmount(PosLong.unsafeFrom(Random.between(10L, 50L)))),
        RewardTransaction(address2, TransactionAmount(PosLong.unsafeFrom(Random.between(20L, 40L)))),
        RewardTransaction(address3, TransactionAmount(PosLong.unsafeFrom(Random.between(30L, 60L))))
      )

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, Map.empty, rewards)
      } yield
        expect.all(
          rewardTxs.nonEmpty,
          newBalances.keySet == base.keySet,
          rewardTxs.forall(tx => newBalances(tx.destination).value.value >= base(tx.destination).value.value)
        )
    }
  }

  test("should merge reward and updated balances even with overlapping addresses") {
    testResources.use { mgr =>
      val base = SortedMap(address1 -> Balance(100L))
      val updates = Map(address1 -> Balance(200L))
      val rewards = SortedSet(RewardTransaction(address1, TransactionAmount(50L)))

      for {
        (newBalances, rewardTxs) <- mgr.acceptRewardTxs(base, updates, rewards)
      } yield
        expect.all(
          rewardTxs.size == 1,
          newBalances(address1).value.value == 250L
        )
    }
  }
}
