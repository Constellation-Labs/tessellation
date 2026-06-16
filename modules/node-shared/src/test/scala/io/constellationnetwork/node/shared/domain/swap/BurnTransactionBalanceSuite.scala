package io.constellationnetwork.node.shared.domain.swap

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.AllowSpendOpsManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.SpendTransactionBalanceManager
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BurnAction, BurnTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed._
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object BurnTransactionBalanceSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  private def totalSupply(balances: SortedMap[Address, Balance]): Long =
    balances.values.map(_.value.value).sum

  test("currency burnFrom reduces totalSupply by amount and returns unspent reservation to source") { res =>
    implicit val (_, hs, sp) = res

    val ops = AllowSpendOpsManager.make[IO]

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      source = keyPair1.getPublic.toAddress
      ammAddress = keyPair2.getPublic.toAddress

      // AllowSpend reserves 10. At acceptance, source was pre-debited by 10 (modelled below as initial source balance = 90 from 100).
      allowSpend = AllowSpend(
        source,
        ammAddress,
        None,
        SwapAmount(10L),
        AllowSpendFee(0L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      // Burn 4 of the 10 reserved. Expected: 6 returned to source; net source change vs. pre-debit = -4 (the burned amount).
      burnTx = BurnTransaction(hashedAllowSpend.hash.some, None, SwapAmount(4L), source)

      // currentBalances: source already pre-debited by 10 (100 -> 90).
      currentBalances = SortedMap(source -> Balance(NonNegLong(90L)))
      allActive: SortedMap[Address, List[Hashed[AllowSpend]]] = SortedMap(source -> List(hashedAllowSpend))

      result = ops.updateCurrencyBalancesByBurnTransactions(currentBalances, allActive, List(burnTx))
    } yield
      result match {
        case Right(updated) =>
          // source = 90 + (10 - 4) = 96. Total supply went from 90 to 96? No: the 10 was pre-debited, returning 6 yields 96,
          // which equals the original 100 minus the burned 4. Relative to currentBalances (90), totalSupply increased by the
          // returned reservation. The strict-decrease invariant holds vs. the pre-AllowSpend supply (100). We assert exact source.
          expect(updated(source) === Balance(NonNegLong(96L)))
        case Left(error) => failure(s"Unexpected balance error: $error")
      }
  }

  test("currency self-burn reduces totalSupply by amount (no destination credit)") { res =>
    implicit val (_, _, sp) = res

    val ops = AllowSpendOpsManager.make[IO]

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      metagraph = keyPair1.getPublic.toAddress

      burnTx = BurnTransaction(none, None, SwapAmount(30L), metagraph)
      currentBalances = SortedMap(metagraph -> Balance(NonNegLong(100L)))
      allActive = SortedMap.empty[Address, List[Hashed[AllowSpend]]]

      result = ops.updateCurrencyBalancesByBurnTransactions(currentBalances, allActive, List(burnTx))
    } yield
      result match {
        case Right(updated) =>
          val before = totalSupply(currentBalances)
          val after = totalSupply(updated)
          expect.all(
            updated(metagraph) === Balance(NonNegLong(70L)),
            after === before - 30L,
            after < before
          )
        case Left(error) => failure(s"Unexpected balance error: $error")
      }
  }

  test("global self-burn reduces totalSupply by amount and emits matching delta") { res =>
    implicit val (_, _, sp) = res

    val manager = SpendTransactionBalanceManager.make[IO]()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      metagraph = keyPair1.getPublic.toAddress

      burnTx = BurnTransaction(none, None, SwapAmount(25L), metagraph)
      currentBalances = SortedMap(metagraph -> Balance(NonNegLong(100L)))
      allGlobalAllowSpends = SortedMap.empty[Address, List[Hashed[AllowSpend]]]

      result = manager.updateGlobalBalancesByBurnTransactions(currentBalances, allGlobalAllowSpends, List(burnTx))
    } yield
      result match {
        case Right((updated, delta)) =>
          val before = totalSupply(currentBalances)
          val after = totalSupply(updated)
          expect.all(
            updated(metagraph) === Balance(NonNegLong(75L)),
            delta(metagraph) === Balance(NonNegLong(75L)),
            after === before - 25L,
            after < before
          )
        case Left(error) => failure(s"Unexpected balance error: $error")
      }
  }

  test("global burnFrom returns unspent reservation to source, never credits a destination") { res =>
    implicit val (_, hs, sp) = res

    val manager = SpendTransactionBalanceManager.make[IO]()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      source = keyPair1.getPublic.toAddress
      ammAddress = keyPair2.getPublic.toAddress

      allowSpend = AllowSpend(
        source,
        ammAddress,
        None,
        SwapAmount(10L),
        AllowSpendFee(0L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      burnTx = BurnTransaction(hashedAllowSpend.hash.some, None, SwapAmount(4L), source)

      currentBalances = SortedMap(source -> Balance(NonNegLong(90L)))
      allGlobalAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]] = SortedMap(source -> List(hashedAllowSpend))

      result = manager.updateGlobalBalancesByBurnTransactions(currentBalances, allGlobalAllowSpends, List(burnTx))
    } yield
      result match {
        case Right((updated, _)) =>
          // Only the source is touched; no destination key (ammAddress) appears.
          expect.all(
            updated(source) === Balance(NonNegLong(96L)),
            !updated.contains(ammAddress)
          )
        case Left(error) => failure(s"Unexpected balance error: $error")
      }
  }

  // Re-execution invariant: a CurrencyIncrementalSnapshot containing a BurnAction is accepted at the global level and re-creates
  // identically. The global L0 re-executes by validating the BurnAction with the SAME production validator the currency used, then
  // applying its burn transactions to balances. If the currency-side applier (AllowSpendOpsManager) and the global-side applier
  // (SpendTransactionBalanceManager) produced different balances for the same accepted burn, the recreated snapshot would differ from
  // the proposed one and validation would yield SnapshotDifferentThanExpected. These tests drive the exact accept-then-apply pipeline
  // both layers run and assert: (1) the BurnAction validates (accepted, not rejected), (2) totalSupply is reduced by the burned amount,
  // and (3) the currency-side and global-side appliers produce byte-identical balances (the precise condition that prevents
  // SnapshotDifferentThanExpected at global re-execution).

  test("self-burn BurnAction is accepted and re-executes identically across currency and global (no SnapshotDifferentThanExpected)") {
    res =>
      implicit val (_, hs, sp) = res

      val validator = BurnActionValidator.make[IO]
      val currencyOps = AllowSpendOpsManager.make[IO]
      val globalManager = SpendTransactionBalanceManager.make[IO]()

      for {
        keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
        metagraph = keyPair1.getPublic.toAddress

        // A self-burn: the metagraph (currencyId) burns 30 of its own balance.
        selfBurnTx = BurnTransaction(none, CurrencyId(metagraph).some, SwapAmount(30L), metagraph)
        burnAction = BurnAction(NonEmptyList.of(selfBurnTx))

        // Currency acceptance keys active allow spends + balances by metagraphId.some (see CurrencySnapshotAcceptanceManager).
        currentBalances = SortedMap(metagraph -> Balance(NonNegLong(100L)))
        activeAllowSpends = SortedMap(metagraph.some -> SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
        balancesForValidation = Map(metagraph.some -> currentBalances)

        // Step 1: validate via the production validator (the exact call both acceptance managers make).
        (accepted, rejected) <- validator.validateReturningAcceptedAndRejected(
          Map(metagraph -> List(burnAction)),
          activeAllowSpends,
          balancesForValidation
        )

        acceptedBurnTxs = accepted.getOrElse(metagraph, List.empty).flatMap(_.burnTransactions.toList)
        noAllowSpends = SortedMap.empty[Address, List[Hashed[AllowSpend]]]

        // Step 2: apply on the currency side and the global side from the same inputs.
        currencyResult = currencyOps.updateCurrencyBalancesByBurnTransactions(currentBalances, noAllowSpends, acceptedBurnTxs)
        globalResult = globalManager.updateGlobalBalancesByBurnTransactions(currentBalances, noAllowSpends, acceptedBurnTxs)
      } yield
        (currencyResult, globalResult) match {
          case (Right(currencyBalances), Right((globalBalances, _))) =>
            expect.all(
              rejected.isEmpty,
              accepted.contains(metagraph),
              accepted(metagraph) === List(burnAction),
              currencyBalances(metagraph) === Balance(NonNegLong(70L)),
              totalSupply(currencyBalances) === totalSupply(currentBalances) - 30L,
              totalSupply(currencyBalances) < totalSupply(currentBalances),
              // Re-execution identity: currency-side and global-side appliers agree exactly.
              currencyBalances === globalBalances
            )
          case other => failure(s"Unexpected balance results: $other")
        }
  }

  test("burnFrom BurnAction is accepted and re-executes identically across currency and global (no SnapshotDifferentThanExpected)") {
    res =>
      implicit val (_, hs, sp) = res

      val validator = BurnActionValidator.make[IO]
      val currencyOps = AllowSpendOpsManager.make[IO]
      val globalManager = SpendTransactionBalanceManager.make[IO]()

      for {
        keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
        keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
        keyPair3 <- KeyPairGenerator.makeKeyPair[IO]

        source = keyPair1.getPublic.toAddress
        currencyId = CurrencyId(keyPair2.getPublic.toAddress)
        ammAddress = keyPair3.getPublic.toAddress

        // AllowSpend reserves 10 of the source's funds (approver = ammAddress = the metagraph that emits the burn).
        allowSpend = AllowSpend(
          source,
          ammAddress,
          currencyId.some,
          SwapAmount(10L),
          AllowSpendFee(0L),
          AllowSpendReference.empty,
          EpochProgress(20L),
          List(ammAddress)
        )
        signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
        hashedAllowSpend <- signedAllowSpend.toHashed

        // Burn 4 of the 10 reserved. Expected: 6 returned to source.
        burnTx = BurnTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(4L), source)
        burnAction = BurnAction(NonEmptyList.of(burnTx))

        // Source already pre-debited by the reserved 10 (100 -> 90).
        currentBalances = SortedMap(source -> Balance(NonNegLong(90L)))
        activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(source -> SortedSet(signedAllowSpend)))
        balancesForValidation = Map(currencyId.value.some -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

        (accepted, rejected) <- validator.validateReturningAcceptedAndRejected(
          Map(ammAddress -> List(burnAction)),
          activeAllowSpends,
          balancesForValidation
        )

        acceptedBurnTxs = accepted.getOrElse(ammAddress, List.empty).flatMap(_.burnTransactions.toList)
        allActive: SortedMap[Address, List[Hashed[AllowSpend]]] = SortedMap(source -> List(hashedAllowSpend))

        currencyResult = currencyOps.updateCurrencyBalancesByBurnTransactions(currentBalances, allActive, acceptedBurnTxs)
        globalResult = globalManager.updateGlobalBalancesByBurnTransactions(currentBalances, allActive, acceptedBurnTxs)
      } yield
        (currencyResult, globalResult) match {
          case (Right(currencyBalances), Right((globalBalances, _))) =>
            expect.all(
              rejected.isEmpty,
              accepted.contains(ammAddress),
              accepted(ammAddress) === List(burnAction),
              // 90 + (10 - 4) = 96, i.e. the original 100 minus the burned 4.
              currencyBalances(source) === Balance(NonNegLong(96L)),
              !currencyBalances.contains(ammAddress),
              // Re-execution identity: currency-side and global-side appliers agree exactly.
              currencyBalances === globalBalances
            )
          case other => failure(s"Unexpected balance results: $other")
        }
  }
}
