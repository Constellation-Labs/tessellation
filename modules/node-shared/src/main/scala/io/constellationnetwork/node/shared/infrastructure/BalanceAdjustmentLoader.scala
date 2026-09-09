package io.constellationnetwork.node.shared.infrastructure

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.io.Source
import scala.util.{Failure, Success, Try}

import io.constellationnetwork.env.AppEnvironment.Mainnet
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments.{
  AdjustmentType,
  BalanceAdjustmentAtOrdinal,
  RequiredAdjustment
}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.parser.decode

object BalanceAdjustmentLoader {

  @derive(encoder, decoder, eqv, show)
  case class JsonAdjustment(
    address: Address,
    reason: String,
    reference: List[String],
    deduct: Option[Long] = None,
    increase: Option[Long] = None
  )

  @derive(encoder, decoder, eqv, show)
  case class JsonCurrencyAdjustments(
    currencyId: Address,
    snapshotOrdinal: SnapshotOrdinal,
    adjustments: List[JsonAdjustment],
    enforceExactMatch: Option[Boolean] = None
  )

  /** Helper method to create a balance adjustment function for a specific currency */
  def createBalanceAdjustmentFunction(
    currencyRequiredAdjustments: Set[RequiredAdjustment]
  ): (SortedMap[Address, Balance], Set[BalanceAdjustment]) => Either[String, SortedMap[Address, Balance]] = {
    (currentBalances, balanceAdjustments) =>
      CurrencyBalanceAdjustments.applyAndValidateBalanceAdjustments(
        currentBalances,
        balanceAdjustments,
        currencyRequiredAdjustments
      )
  }

  /** Exact authorization is opt-in so tightening a newly added incident block does not change how historical adjustment blocks replay. */
  def createExactBalanceAdjustmentFunction(
    authorizedAdjustments: Set[BalanceAdjustment]
  ): (SortedMap[Address, Balance], Set[BalanceAdjustment]) => Either[String, SortedMap[Address, Balance]] = {
    (currentBalances, balanceAdjustments) =>
      CurrencyBalanceAdjustments.applyAndValidateExactBalanceAdjustments(
        currentBalances,
        balanceAdjustments,
        authorizedAdjustments
      )
  }

  /** Load adjustments and create BalanceAdjustmentAtOrdinal entries for integration with metagraphsBalancesAdjustments.
    *
    * A metagraph may hold several blocks, one per ordinal at which it adjusts balances, so entries are grouped rather than keyed uniquely.
    * Keying uniquely meant the last block in the file silently retired every earlier block for the same currency, which both broke replay
    * of those ordinals and made a follow-up adjustment impossible to schedule.
    */
  def loadAndCreateAdjustmentEntries(
    resourcePath: String
  ): Either[String, Map[Address, List[BalanceAdjustmentAtOrdinal]]] =
    for {
      jsonString <- readResourceFile(resourcePath)
      jsonAdjustments <- parseJsonToModel(jsonString)
      adjustmentEntries <- convertToAdjustmentEntries(jsonAdjustments)
    } yield adjustmentEntries

  def convertToAdjustmentEntries(
    jsonAdjustments: List[JsonCurrencyAdjustments]
  ): Either[String, Map[Address, List[BalanceAdjustmentAtOrdinal]]] = {

    val convertedEntries = jsonAdjustments.map { currencyAdj =>
      val convertedAdjustments = currencyAdj.adjustments.map { adj =>
        convertSingleBalanceAdjustment(adj)
      }

      // Separate successful conversions from errors for this currency
      val (errors, adjustments) = convertedAdjustments.partitionMap(identity)

      if (errors.nonEmpty) {
        Left(s"Conversion errors for currency ${currencyAdj.currencyId}: ${errors.mkString(", ")}")
      } else {
        val snapshotOrdinal = currencyAdj.snapshotOrdinal

        val authorizedAdjustments = adjustments.toSet
        val exactMatchRequired = currencyAdj.enforceExactMatch.contains(true)
        val balanceAdjustFunction =
          if (exactMatchRequired)
            createExactBalanceAdjustmentFunction(authorizedAdjustments)
          else
            createBalanceAdjustmentFunction(authorizedAdjustments.map(toRequiredAdjustment))

        val adjustmentEntry = BalanceAdjustmentAtOrdinal(
          snapshotOrdinal = snapshotOrdinal,
          environment = Mainnet,
          exactMatchRequired = exactMatchRequired,
          balanceAdjustFunction = balanceAdjustFunction
        )

        Right((currencyAdj.currencyId, adjustmentEntry))
      }
    }

    // Check if any currency had conversion errors
    val (currencyErrors, successfulEntries) = convertedEntries.partitionMap(identity)

    if (currencyErrors.nonEmpty) {
      Left(currencyErrors.mkString("; "))
    } else {
      Right(successfulEntries.groupMap(_._1)(_._2))
    }
  }

  def readResourceFile(resourcePath: String): Either[String, String] =
    Try {
      val stream = getClass.getResourceAsStream(resourcePath)
      if (stream == null) {
        throw new RuntimeException(s"Resource not found: $resourcePath")
      }
      val source = Source.fromInputStream(stream)
      try
        source.mkString
      finally {
        source.close()
        stream.close()
      }
    } match {
      case Success(content)   => Right(content)
      case Failure(exception) => Left(s"Failed to read resource file: ${exception.getMessage}")
    }

  def parseJsonToModel(jsonString: String): Either[String, List[JsonCurrencyAdjustments]] =
    decode[List[JsonCurrencyAdjustments]](jsonString)
      .leftMap(error => s"Failed to parse JSON: ${error.getMessage}")

  def convertToBalanceAdjustments(
    jsonAdjustments: List[JsonCurrencyAdjustments]
  ): Either[String, Map[Address, Set[RequiredAdjustment]]] = {

    // Convert each currency's adjustments
    val convertedByCurrency = jsonAdjustments.map { currencyAdj =>
      val convertedAdjustments = currencyAdj.adjustments.map { adj =>
        convertSingleAdjustment(adj)
      }

      // Separate successful conversions from errors for this currency
      val (errors, adjustments) = convertedAdjustments.partitionMap(identity)

      if (errors.nonEmpty) {
        Left(s"Conversion errors for currency ${currencyAdj.currencyId}: ${errors.mkString(", ")}")
      } else {
        Right((currencyAdj.currencyId, adjustments.toSet))
      }
    }

    // Check if any currency had conversion errors
    val (currencyErrors, successfulCurrencies) = convertedByCurrency.partitionMap(identity)

    if (currencyErrors.nonEmpty) {
      Left(currencyErrors.mkString("; "))
    } else {
      Right(successfulCurrencies.toMap)
    }
  }

  def convertSingleAdjustment(jsonAdj: JsonAdjustment): Either[String, RequiredAdjustment] =
    for {
      adjustmentType <- parseAdjustmentType(jsonAdj.deduct, jsonAdj.increase)
    } yield
      RequiredAdjustment(
        address = jsonAdj.address,
        adjustment = adjustmentType
      )

  def convertSingleBalanceAdjustment(jsonAdj: JsonAdjustment): Either[String, BalanceAdjustment] =
    for {
      reason <- parseAdjustmentReason(jsonAdj.reason)
      adjustmentType <- parseAdjustmentType(jsonAdj.deduct, jsonAdj.increase)
    } yield
      adjustmentType match {
        case AdjustmentType.Increase(amount) =>
          BalanceAdjustment(jsonAdj.address, reason, SortedSet.from(jsonAdj.reference.map(Hash(_))), amount.some, none)
        case AdjustmentType.Decrease(amount) =>
          BalanceAdjustment(jsonAdj.address, reason, SortedSet.from(jsonAdj.reference.map(Hash(_))), none, amount.some)
      }

  def parseAdjustmentReason(reason: String): Either[String, BalanceAdjustmentReason] =
    reason match {
      case "SpendTransactionNotApplied"            => SpendTransactionNotApplied.asRight
      case "SpendTransactionSourceNotApplied"      => SpendTransactionSourceNotApplied.asRight
      case "SpendTransactionDestinationNotApplied" => SpendTransactionDestinationNotApplied.asRight
      case "TokenUnlockBugDeduction"               => TokenUnlockBugDeduction.asRight
      case "FeeTransactionBugDeduction"            => FeeTransactionBugDeduction.asRight
      case other                                   => Left(s"Unknown balance adjustment reason: $other")
    }

  private def toRequiredAdjustment(adjustment: BalanceAdjustment): RequiredAdjustment = {
    val adjustmentType = adjustment.increase
      .map(AdjustmentType.Increase(_))
      .orElse(adjustment.deduct.map(AdjustmentType.Decrease(_)))
      .getOrElse(throw new IllegalArgumentException("Balance adjustment must contain an increase or deduction"))

    RequiredAdjustment(adjustment.address, adjustmentType)
  }

  def parseAdjustmentType(
    deduct: Option[Long],
    increase: Option[Long]
  ): Either[String, AdjustmentType] =
    (deduct, increase) match {
      case (Some(deductAmount), None) =>
        Right(AdjustmentType.Decrease(Amount(NonNegLong.unsafeFrom(math.abs(deductAmount)))))
      case (None, Some(increaseAmount)) =>
        Right(AdjustmentType.Increase(Amount(NonNegLong.unsafeFrom(increaseAmount))))
      case (None, None) =>
        Left("Either deduct or increase must be specified")
      case (Some(_), Some(_)) =>
        Left("Cannot have both deduct and increase in the same adjustment")
    }
}
