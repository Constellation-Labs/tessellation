package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.AllowSpendOpsManager
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.MutableIOSuite

/** PROT-1691: a metagraph's `info.activeAllowSpends` necessarily lags the global layer, because a metagraph only learns that its
  * SpendAction was accepted from the global snapshot that accepted it. Folding that lagging self-report back into the global layer's own
  * map re-adds references the global layer has already retired, and a re-added reference can be presented and settled again.
  */
object AllowSpendStateManagerResurrectionSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  private val manager = AllowSpendStateManager.make[IO]()
  private val currencyBalanceManager = AllowSpendOpsManager.make[IO]
  private val globalBalanceManager = SpendTransactionBalanceManager.make[IO]()

  private val expiry = EpochProgress(500L)
  private val currentEpoch = EpochProgress(10L)

  // `amount` only varies so that the two allow-spends in a test hash differently.
  private def allowSpend(source: Address, destination: Address, amount: Long): AllowSpend =
    AllowSpend(
      source,
      destination,
      None,
      SwapAmount(PosLong.unsafeFrom(amount)),
      AllowSpendFee(1L),
      AllowSpendReference.empty,
      expiry,
      List(destination)
    )

  private case class SettlementFixture(
    source: Address,
    destination: Address,
    signedAllowSpend: Signed[AllowSpend],
    hashedAllowSpend: Hashed[AllowSpend],
    spendTransaction: SpendTransaction
  )

  private def settlementFixture(implicit hasher: Hasher[IO], securityProvider: SecurityProvider[IO]): IO[SettlementFixture] =
    for {
      sourceKey <- KeyPairGenerator.makeKeyPair[IO]
      destinationKey <- KeyPairGenerator.makeKeyPair[IO]
      source = sourceKey.getPublic.toAddress
      destination = destinationKey.getPublic.toAddress
      signed <- Signed.forAsyncHasher(allowSpend(source, destination, 1000L), sourceKey)
      hashed <- signed.toHashed
      transaction = SpendTransaction(hashed.hash.some, none, SwapAmount(1000L), source, destination)
    } yield SettlementFixture(source, destination, signed, hashed, transaction)

  test("does not re-add a retired reference reported again by a lagging metagraph snapshot") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      replayed <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      untouched <- Signed.forAsyncHasher(allowSpend(user, amm, 200L), userKey)
      replayedHash <- replayed.toHashed.map(_.hash)

      // The metagraph keeps reporting both allow-spends until it syncs the global snapshot that spent the first one.
      metagraphReport = SortedMap(metagraphId -> SortedMap(user -> SortedSet(replayed, untouched)))
      spendTxn = SpendTransaction(replayedHash.some, None, SwapAmount(100L), user, amm)

      // Round 1: the spend action is accepted, so the reference is retired.
      spendResult <- manager.acceptAllowSpends(
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        SortedMap.empty,
        List(spendTxn),
        SortedMap.empty,
        preventAllowSpendResurrection = true
      )

      // Round 2: the metagraph has not synced yet and reports the spent allow-spend again. No spend action this round.
      replayResult <- manager.acceptAllowSpends(
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        spendResult.fullState,
        List.empty,
        spendResult.retiredRefs,
        preventAllowSpendResurrection = true
      )

      activeAfterSpend = spendResult.fullState
        .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
      activeAfterReplay = replayResult.fullState
        .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield
      expect(!activeAfterSpend.contains(replayed)) &&
        expect(activeAfterSpend.contains(untouched)) &&
        expect(
          spendResult.retiredRefs
            .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
            .getOrElse(user, SortedMap.empty[Hash, EpochProgress])
            .contains(replayedHash)
        ) &&
        expect(!activeAfterReplay.contains(replayed)) &&
        expect(activeAfterReplay.contains(untouched))
  }

  test("below the activation ordinal the lagging report still resurrects the reference") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      replayed <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      replayedHash <- replayed.toHashed.map(_.hash)

      metagraphReport = SortedMap(metagraphId -> SortedMap(user -> SortedSet(replayed)))
      spendTxn = SpendTransaction(replayedHash.some, None, SwapAmount(100L), user, amm)

      spendResult <- manager.acceptAllowSpends(
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        SortedMap.empty,
        List(spendTxn),
        SortedMap.empty,
        preventAllowSpendResurrection = false
      )

      replayResult <- manager.acceptAllowSpends(
        currentEpoch,
        metagraphReport,
        SortedMap.empty,
        spendResult.fullState,
        List.empty,
        spendResult.retiredRefs,
        preventAllowSpendResurrection = false
      )

      activeAfterReplay = replayResult.fullState
        .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield
      expect(spendResult.retiredRefs.isEmpty) &&
        expect(activeAfterReplay.contains(replayed))
  }

  test("forgets a retired reference once its allow-spend can no longer be presented") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      spent <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      spentHash <- spent.toHashed.map(_.hash)

      retired = SortedMap(metagraphId.some -> SortedMap(user -> SortedMap(spentHash -> expiry)))

      // At an epoch past the allow-spend's lastValidEpochProgress the unexpired filter rejects it on its own,
      // so keeping the entry would grow the ledger without bound.
      expiryResult <- manager.acceptAllowSpends(
        EpochProgress(NonNegLong.unsafeFrom(expiry.value.value + 1L)),
        SortedMap(metagraphId -> SortedMap(user -> SortedSet(spent))),
        SortedMap.empty,
        SortedMap.empty,
        List.empty,
        retired,
        preventAllowSpendResurrection = true
      )
    } yield
      expect(
        !expiryResult.retiredRefs
          .getOrElse(metagraphId.some, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
          .getOrElse(user, SortedMap.empty[Hash, EpochProgress])
          .contains(spentHash)
      )
  }

  test("retires references per currency, not across currencies") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]
      metagraphKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress
      metagraphId = metagraphKey.getPublic.toAddress

      globalAllowSpend <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      globalHash <- globalAllowSpend.toHashed.map(_.hash)

      // The same hash marked retired under a metagraph must not suppress the global layer's own allow-spend.
      retiredUnderMetagraph = SortedMap(metagraphId.some -> SortedMap(user -> SortedMap(globalHash -> expiry)))

      result <- manager.acceptAllowSpends(
        currentEpoch,
        SortedMap.empty,
        SortedMap(user -> SortedSet(globalAllowSpend)),
        SortedMap.empty,
        List.empty,
        retiredUnderMetagraph,
        preventAllowSpendResurrection = true
      )

      activeGlobal = result.fullState
        .getOrElse(none[Address], SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield expect(activeGlobal.contains(globalAllowSpend))
  }

  test("does not re-add a retired reference on the global layer either") { res =>
    implicit val (_, h, sp) = res

    for {
      userKey <- KeyPairGenerator.makeKeyPair[IO]
      ammKey <- KeyPairGenerator.makeKeyPair[IO]

      user = userKey.getPublic.toAddress
      amm = ammKey.getPublic.toAddress

      retiredSpend <- Signed.forAsyncHasher(allowSpend(user, amm, 100L), userKey)
      retiredHash <- retiredSpend.toHashed.map(_.hash)

      retired = SortedMap(none[Address] -> SortedMap(user -> SortedMap(retiredHash -> expiry)))

      result <- manager.acceptAllowSpends(
        currentEpoch,
        SortedMap.empty,
        SortedMap(user -> SortedSet(retiredSpend)),
        SortedMap.empty,
        List.empty,
        retired,
        preventAllowSpendResurrection = true
      )

      activeGlobal = result.fullState
        .getOrElse(none[Address], SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        .getOrElse(user, SortedSet.empty[Signed[AllowSpend]])
    } yield expect(!activeGlobal.contains(retiredSpend))
  }

  test("consumes a settled reference during the currency balance fold") { res =>
    implicit val (_, h, sp) = res

    settlementFixture.map { fixture =>
      val active = SortedMap(fixture.source -> List(fixture.hashedAllowSpend))
      val transactions = List(fixture.spendTransaction, fixture.spendTransaction)
      val balances = SortedMap(fixture.source -> Balance.empty, fixture.destination -> Balance.empty)

      val legacy = currencyBalanceManager.updateCurrencyBalancesBySpendTransactions(
        balances,
        active,
        transactions,
        consumeSettledAllowSpends = false
      )
      val fixed = currencyBalanceManager.updateCurrencyBalancesBySpendTransactions(
        balances,
        active,
        transactions,
        consumeSettledAllowSpends = true
      )

      expect(legacy.exists(_.get(fixture.destination).contains(Balance(2000L)))) && expect(fixed.isLeft)
    }
  }

  test("consumes a settled reference during the global balance fold") { res =>
    implicit val (_, h, sp) = res

    settlementFixture.map { fixture =>
      val active = SortedMap(fixture.source -> List(fixture.hashedAllowSpend))
      val transactions = List(fixture.spendTransaction, fixture.spendTransaction)
      val balances = SortedMap(fixture.source -> Balance.empty, fixture.destination -> Balance.empty)

      val legacy = globalBalanceManager.updateGlobalBalancesBySpendTransactions(
        balances,
        active,
        transactions,
        consumeSettledAllowSpends = false
      )
      val fixed = globalBalanceManager.updateGlobalBalancesBySpendTransactions(
        balances,
        active,
        transactions,
        consumeSettledAllowSpends = true
      )

      expect(legacy.exists(_._1.get(fixture.destination).contains(Balance(2000L)))) && expect(fixed.isLeft)
    }
  }

  test("does not refund an expired global allow-spend settled in the same snapshot") { res =>
    implicit val (_, h, sp) = res

    for {
      fixture <- settlementFixture
      active = SortedMap(fixture.source -> SortedSet(fixture.signedAllowSpend))
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        active,
        EpochProgress(501L),
        List(fixture.spendTransaction),
        suppressSpent = true
      )
      legacyRefundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        active,
        EpochProgress(501L),
        List(fixture.spendTransaction),
        suppressSpent = false
      )
    } yield expect(refundable.values.forall(_.isEmpty)) && expect(legacyRefundable == active)
  }

  test("continues refunding an unspent expired global allow-spend") { res =>
    implicit val (_, h, sp) = res

    for {
      fixture <- settlementFixture
      active = SortedMap(fixture.source -> SortedSet(fixture.signedAllowSpend))
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        active,
        EpochProgress(501L),
        List.empty,
        suppressSpent = true
      )
    } yield expect(refundable == active)
  }
}
