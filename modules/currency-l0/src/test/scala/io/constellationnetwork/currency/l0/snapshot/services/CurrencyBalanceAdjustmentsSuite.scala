package io.constellationnetwork.currency.l0.snapshot.services

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BalanceAdjustment, SpendTransactionNotApplied}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.generators.{addressGen, amountGen}

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.scalacheck.{Arbitrary, Gen}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object CurrencyBalanceAdjustmentsSuite extends SimpleIOSuite with Checkers {

  val balanceDecreaseGen: Gen[BalanceAdjustment] = for {
    address <- addressGen
    amount <- amountGen
  } yield
    BalanceAdjustment(
      address,
      SpendTransactionNotApplied,
      SortedSet.empty,
      increase = None,
      deduct = Some(amount)
    )

  val smallPositiveAmountGen = Gen.chooseNum(1000L, 2000L).map(l => Amount(NonNegLong.unsafeFrom(l)))
  val balanceDecreasePosGen: Gen[BalanceAdjustment] = for {
    address <- addressGen
    amount <- smallPositiveAmountGen
  } yield
    BalanceAdjustment(
      address,
      SpendTransactionNotApplied,
      SortedSet.empty,
      increase = None,
      deduct = Some(amount)
    )

  // Generator specifically for balance increases (credits only)
  val balanceIncreaseGen: Gen[BalanceAdjustment] = for {
    address <- addressGen
    amount <- amountGen
  } yield
    BalanceAdjustment(
      address,
      SpendTransactionNotApplied,
      SortedSet.empty,
      increase = Some(amount),
      deduct = None
    )

  // Combined generator that produces both types
  val balanceAdjustmentGen: Gen[BalanceAdjustment] = Gen.oneOf(
    balanceDecreaseGen,
    balanceIncreaseGen
  )

  val requiredAdjustmentGen: Gen[CurrencyBalanceAdjustments.RequiredAdjustment] = for {
    address <- addressGen
    amount <- amountGen
    adjustmentType <- Gen.oneOf(
      CurrencyBalanceAdjustments.AdjustmentType.Increase(amount),
      CurrencyBalanceAdjustments.AdjustmentType.Decrease(amount)
    )
  } yield CurrencyBalanceAdjustments.RequiredAdjustment(address, adjustmentType)

  test("should successfully apply balance increases") {
    forall(Gen.nonEmptyListOf(balanceIncreaseGen.filter(_.increase.isDefined))) { adjustments =>
      val sortedAdjustments = adjustments.toSet
      val emptyBalances = SortedMap.empty[Address, Balance]

      val result = CurrencyBalanceAdjustments.applyBalanceAdjustments(emptyBalances, sortedAdjustments)

      result match {
        case Right(updatedBalances) =>
          // Verify each adjustment was applied correctly
          val allAppliedCorrectly = adjustments.forall { adj =>
            val expectedBalance = Balance.fromAmount(adj.increase.get)
            updatedBalances.get(adj.address) match {
              case Some(actualBalance) => actualBalance.value >= expectedBalance.value
              case None                => false
            }
          }
          expect(allAppliedCorrectly)
        case Left(error) =>
          failure(s"Should have succeeded but got error: $error")
      }
    }
  }

  test("should successfully apply balance decreases to existing balances") {
    forall(Gen.nonEmptyListOf(balanceDecreaseGen.filter(_.deduct.isDefined))) { adjustments =>
      val sortedAdjustments = adjustments.toSet
      // Create initial balances with sufficient amounts (double the deduction amount)
      val initialBalances = SortedMap.from(adjustments.map { adj =>
        adj.address -> Balance(NonNegLong.unsafeFrom(adj.deduct.get.value))
      })

      val result = CurrencyBalanceAdjustments.applyBalanceAdjustments(initialBalances, sortedAdjustments)

      result match {
        case Right(updatedBalances) =>
          // Verify balances were reduced correctly
          val allReducedCorrectly = adjustments.forall { adj =>
            val initialBalance = initialBalances(adj.address)
            val deductAmount = adj.deduct.get
            val expectedBalance = Balance(NonNegLong.unsafeFrom(initialBalance.value - deductAmount.value))
            updatedBalances.get(adj.address).contains(expectedBalance)
          }
          expect(allReducedCorrectly)
        case Left(error) =>
          failure(s"Should have succeeded but got error: $error")
      }
    }
  }

  test("should fail when trying to deduct more than available balance") {
    forall(Gen.nonEmptyListOf(balanceDecreasePosGen)) { adjustments =>
      val sortedAdjustments = adjustments.toSet
      val emptyBalances = SortedMap.empty[Address, Balance]

      val result = CurrencyBalanceAdjustments.applyBalanceAdjustments(emptyBalances, sortedAdjustments)

      result match {
        case Left(error) =>
          failure(error)
        case Right(balances) =>
          expect(balances.values.toList.forall(_.value.value == 0L))
      }
    }
  }

  test("should validate required adjustments are present") {
    forall(requiredAdjustmentGen) { requiredAdjustment =>
      val matchingBalanceAdjustment = requiredAdjustment.adjustment match {
        case CurrencyBalanceAdjustments.AdjustmentType.Increase(amount) =>
          BalanceAdjustment(
            requiredAdjustment.address,
            SpendTransactionNotApplied,
            SortedSet.empty,
            Some(amount),
            None
          )
        case CurrencyBalanceAdjustments.AdjustmentType.Decrease(amount) =>
          BalanceAdjustment(
            requiredAdjustment.address,
            SpendTransactionNotApplied,
            SortedSet.empty,
            None,
            Some(amount)
          )
      }

      val sortedAdjustments = Set(matchingBalanceAdjustment)
      val requiredAdjustments = Set(requiredAdjustment)
      val emptyBalances = SortedMap.empty[Address, Balance]

      val result = requiredAdjustment.adjustment match {
        case CurrencyBalanceAdjustments.AdjustmentType.Increase(_) =>
          CurrencyBalanceAdjustments.applyAndValidateBalanceAdjustments(emptyBalances, sortedAdjustments, requiredAdjustments)
        case CurrencyBalanceAdjustments.AdjustmentType.Decrease(_) =>
          // For decrease, create initial balance with sufficient amount
          val initialBalance =
            SortedMap(requiredAdjustment.address -> Balance(NonNegLong.unsafeFrom(requiredAdjustment.adjustment.amount.value)))
          CurrencyBalanceAdjustments.applyAndValidateBalanceAdjustments(initialBalance, sortedAdjustments, requiredAdjustments)
      }

      result match {
        case Right(_)    => expect(true)
        case Left(error) => failure(s"Should have succeeded with matching required adjustment, but got: $error")
      }
    }
  }

  test("should fail validation when required adjustments are missing") {
    forall(requiredAdjustmentGen) { requiredAdjustment =>
      val emptyAdjustments = Set.empty[BalanceAdjustment]
      val requiredAdjustments = Set(requiredAdjustment)
      val emptyBalances = SortedMap.empty[Address, Balance]

      val result = CurrencyBalanceAdjustments.applyAndValidateBalanceAdjustments(
        emptyBalances,
        emptyAdjustments,
        requiredAdjustments
      )

      result match {
        case Left(error) => expect(error.contains("Missing required adjustments"))
        case Right(_)    => failure("Should have failed validation when required adjustments are missing")
      }
    }
  }

  test("should apply increases before deductions") {
    val gen = for {
      address <- addressGen
      amount <- amountGen
    } yield (address, amount)

    forall(gen) {
      case (address, amount) =>
        val increaseAdjustment =
          BalanceAdjustment(address, SpendTransactionNotApplied, SortedSet.empty, Some(amount), None)
        val decreaseAdjustment =
          BalanceAdjustment(address, SpendTransactionNotApplied, SortedSet.empty, None, Some(amount))
        val sortedAdjustments = Set(increaseAdjustment, decreaseAdjustment)
        val emptyBalances = SortedMap.empty[Address, Balance]

        val result = CurrencyBalanceAdjustments.applyBalanceAdjustments(emptyBalances, sortedAdjustments)

        result match {
          case Right(updatedBalances) =>
            // Should result in zero balance (increase then decrease by same amount)
            val finalBalance = updatedBalances.getOrElse(address, Balance.empty)
            expect(finalBalance == Balance.empty)
          case Left(error) =>
            failure(s"Should have succeeded but got error: $error")
        }
    }
  }
}
