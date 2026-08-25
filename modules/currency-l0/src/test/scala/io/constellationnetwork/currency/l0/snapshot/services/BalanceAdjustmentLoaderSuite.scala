package io.constellationnetwork.currency.l0.snapshot.services

import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.infrastructure.BalanceAdjustmentLoader
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.generators.addressGen

import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.{Arbitrary, Gen}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BalanceAdjustmentLoaderSuite extends SimpleIOSuite with Checkers {

  // Generator for JsonAdjustment
  val jsonAdjustmentIncreaseGen: Gen[BalanceAdjustmentLoader.JsonAdjustment] = for {
    address <- addressGen
    amount <- Gen.chooseNum(1L, 1000000L)
    reason <- Gen.alphaNumStr.filter(_.nonEmpty)
    reference <- Gen.listOfN(Gen.chooseNum(1, 3).sample.getOrElse(1), Gen.alphaNumStr.filter(_.nonEmpty))
  } yield
    BalanceAdjustmentLoader.JsonAdjustment(
      address = address,
      reason = reason,
      reference = reference,
      deduct = None,
      increase = Some(amount)
    )

  val jsonAdjustmentDecreaseGen: Gen[BalanceAdjustmentLoader.JsonAdjustment] = for {
    address <- addressGen
    amount <- Gen.chooseNum(1L, 1000000L)
    reason <- Gen.alphaNumStr.filter(_.nonEmpty)
    reference <- Gen.listOfN(Gen.chooseNum(1, 3).sample.getOrElse(1), Gen.alphaNumStr.filter(_.nonEmpty))
  } yield
    BalanceAdjustmentLoader.JsonAdjustment(
      address = address,
      reason = reason,
      reference = reference,
      deduct = Some(amount),
      increase = None
    )

  val jsonAdjustmentGen: Gen[BalanceAdjustmentLoader.JsonAdjustment] = Gen.oneOf(
    jsonAdjustmentIncreaseGen,
    jsonAdjustmentDecreaseGen
  )

  // Generator for JsonCurrencyAdjustments
  val jsonCurrencyAdjustmentsGen: Gen[BalanceAdjustmentLoader.JsonCurrencyAdjustments] = for {
    currencyId <- addressGen
    snapshotOrdinal <- Gen.chooseNum(1000000L, 9999999999L)
    adjustments <- Gen.nonEmptyListOf(jsonAdjustmentGen)
  } yield
    BalanceAdjustmentLoader.JsonCurrencyAdjustments(
      currencyId = currencyId,
      snapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(snapshotOrdinal)),
      adjustments = adjustments
    )

  // Generator for invalid JsonAdjustment (both deduct and increase)
  val invalidJsonAdjustmentBothGen: Gen[BalanceAdjustmentLoader.JsonAdjustment] = for {
    address <- addressGen
    deductAmount <- Gen.chooseNum(1L, 1000000L)
    increaseAmount <- Gen.chooseNum(1L, 1000000L)
    reason <- Gen.alphaNumStr.filter(_.nonEmpty)
    reference <- Gen.listOfN(1, Gen.alphaNumStr.filter(_.nonEmpty))
  } yield
    BalanceAdjustmentLoader.JsonAdjustment(
      address = address,
      reason = reason,
      reference = reference,
      deduct = Some(deductAmount),
      increase = Some(increaseAmount)
    )

  // Generator for invalid JsonAdjustment (neither deduct nor increase)
  val invalidJsonAdjustmentNeitherGen: Gen[BalanceAdjustmentLoader.JsonAdjustment] = for {
    address <- addressGen
    reason <- Gen.alphaNumStr.filter(_.nonEmpty)
    reference <- Gen.listOfN(1, Gen.alphaNumStr.filter(_.nonEmpty))
  } yield
    BalanceAdjustmentLoader.JsonAdjustment(
      address = address,
      reason = reason,
      reference = reference,
      deduct = None,
      increase = None
    )

  test("should successfully convert JsonAdjustment with increase to RequiredAdjustment") {
    forall(jsonAdjustmentIncreaseGen) { jsonAdj =>
      val result = BalanceAdjustmentLoader.convertSingleAdjustment(jsonAdj)

      result match {
        case Right(requiredAdj) =>
          val expectedAmount = Amount(NonNegLong.unsafeFrom(jsonAdj.increase.get))
          expect(requiredAdj.address == jsonAdj.address) &&
          expect(requiredAdj.adjustment == CurrencyBalanceAdjustments.AdjustmentType.Increase(expectedAmount))
        case Left(error) =>
          failure(s"Should have succeeded but got error: $error")
      }
    }
  }

  test("should successfully convert JsonAdjustment with deduct to RequiredAdjustment") {
    forall(jsonAdjustmentDecreaseGen) { jsonAdj =>
      val result = BalanceAdjustmentLoader.convertSingleAdjustment(jsonAdj)

      result match {
        case Right(requiredAdj) =>
          val expectedAmount = Amount(NonNegLong.unsafeFrom(math.abs(jsonAdj.deduct.get)))
          expect(requiredAdj.address == jsonAdj.address) &&
          expect(requiredAdj.adjustment == CurrencyBalanceAdjustments.AdjustmentType.Decrease(expectedAmount))
        case Left(error) =>
          failure(s"Should have succeeded but got error: $error")
      }
    }
  }

  test("should fail to convert JsonAdjustment with both deduct and increase") {
    forall(invalidJsonAdjustmentBothGen) { jsonAdj =>
      val result = BalanceAdjustmentLoader.convertSingleAdjustment(jsonAdj)

      result match {
        case Left(error) =>
          expect(error.contains("Cannot have both deduct and increase"))
        case Right(_) =>
          failure("Should have failed when both deduct and increase are specified")
      }
    }
  }

  test("should fail to convert JsonAdjustment with neither deduct nor increase") {
    forall(invalidJsonAdjustmentNeitherGen) { jsonAdj =>
      val result = BalanceAdjustmentLoader.convertSingleAdjustment(jsonAdj)

      result match {
        case Left(error) =>
          expect(error.contains("Either deduct or increase must be specified"))
        case Right(_) =>
          failure("Should have failed when neither deduct nor increase are specified")
      }
    }
  }

  test("should successfully convert list of JsonCurrencyAdjustments to Map") {
    forall(Gen.nonEmptyListOf(jsonCurrencyAdjustmentsGen)) { jsonCurrencyAdjustments =>
      // Filter out invalid adjustments to ensure success
      val validJsonCurrencyAdjustments = jsonCurrencyAdjustments.map { currencyAdj =>
        val validAdjustments = currencyAdj.adjustments.filter { adj =>
          (adj.deduct.isDefined && adj.increase.isEmpty) || (adj.increase.isDefined && adj.deduct.isEmpty)
        }
        currencyAdj.copy(adjustments = if (validAdjustments.nonEmpty) validAdjustments else List(jsonAdjustmentIncreaseGen.sample.get))
      }

      val result = BalanceAdjustmentLoader.convertToBalanceAdjustments(validJsonCurrencyAdjustments)

      result match {
        case Right(adjustmentMap) =>
          val allCurrenciesPresent = validJsonCurrencyAdjustments.forall { currencyAdj =>
            adjustmentMap.contains(currencyAdj.currencyId)
          }
          val allAdjustmentsConverted = validJsonCurrencyAdjustments.forall { currencyAdj =>
            adjustmentMap.get(currencyAdj.currencyId) match {
              case Some(requiredAdjustments) =>
                requiredAdjustments.size == currencyAdj.adjustments.size
              case None => false
            }
          }
          expect(allCurrenciesPresent) && expect(allAdjustmentsConverted)
        case Left(error) =>
          failure(s"Should have succeeded but got error: $error")
      }
    }
  }

  test("should create BalanceAdjustmentAtOrdinal with correct ordinal and environment from JSON") {
    forall(
      jsonCurrencyAdjustmentsGen.filter(
        _.adjustments.forall(adj => (adj.deduct.isDefined && adj.increase.isEmpty) || (adj.increase.isDefined && adj.deduct.isEmpty))
      )
    ) { jsonCurrencyAdj =>
      val result = BalanceAdjustmentLoader.convertToAdjustmentEntries(
        List(jsonCurrencyAdj)
      )

      result match {
        case Right(adjustmentEntries) =>
          adjustmentEntries.get(jsonCurrencyAdj.currencyId) match {
            case Some(adjustmentsAtOrdinal) =>
              val expectedOrdinal = jsonCurrencyAdj.snapshotOrdinal
              expect(adjustmentsAtOrdinal.exists(_.snapshotOrdinal == expectedOrdinal))
            case None =>
              failure(s"Should have entry for currency ${jsonCurrencyAdj.currencyId}")
          }
        case Left(error) =>
          failure(s"Should have succeeded but got error: $error")
      }
    }
  }

  test("should handle negative deduct amounts by taking absolute value") {
    forall(addressGen) { address =>
      forall(Gen.chooseNum(-1000000L, -1L)) { negativeAmount =>
        val jsonAdj = BalanceAdjustmentLoader.JsonAdjustment(
          address = address,
          reason = "test",
          reference = List("ref"),
          deduct = Some(negativeAmount),
          increase = None
        )

        val result = BalanceAdjustmentLoader.convertSingleAdjustment(jsonAdj)

        result match {
          case Right(requiredAdj) =>
            val expectedAmount = Amount(NonNegLong.unsafeFrom(math.abs(negativeAmount)))
            expect(requiredAdj.adjustment == CurrencyBalanceAdjustments.AdjustmentType.Decrease(expectedAmount))
          case Left(error) =>
            failure(s"Should have succeeded but got error: $error")
        }
      }
    }
  }

  test("should create balance adjustment function that integrates with CurrencyBalanceAdjustments") {
    forall(jsonAdjustmentIncreaseGen) { jsonAdj =>
      val requiredAdjustments = Set(
        CurrencyBalanceAdjustments.RequiredAdjustment(
          jsonAdj.address,
          CurrencyBalanceAdjustments.AdjustmentType.Increase(Amount(NonNegLong.unsafeFrom(jsonAdj.increase.get)))
        )
      )

      val balanceAdjustFunction = BalanceAdjustmentLoader.createBalanceAdjustmentFunction(requiredAdjustments)
      val emptyBalances = SortedMap.empty[Address, io.constellationnetwork.schema.balance.Balance]
      val emptyAdjustments = Set.empty[io.constellationnetwork.schema.artifact.BalanceAdjustment]

      // This should fail validation because no adjustments are provided
      val result = balanceAdjustFunction(emptyBalances, emptyAdjustments)

      result match {
        case Left(error) =>
          expect(error.contains("Missing required adjustments"))
        case Right(_) =>
          failure("Should have failed validation when required adjustments are missing")
      }
    }
  }
}
