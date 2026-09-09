package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.Mainnet
import io.constellationnetwork.node.shared.infrastructure.BalanceAdjustmentLoader
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.BalanceAdjustment
import io.constellationnetwork.schema.balance.{Amount, Balance}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object CurrencyBalanceAdjustments {

  @derive(eqv, show)
  sealed trait AdjustmentType {
    def amount: Amount
  }
  object AdjustmentType {
    case class Increase(amount: Amount) extends AdjustmentType
    case class Decrease(amount: Amount) extends AdjustmentType
  }

  @derive(eqv, show)
  case class RequiredAdjustment(
    address: Address,
    adjustment: AdjustmentType
  )

  case class BalanceAdjustmentAtOrdinal(
    snapshotOrdinal: SnapshotOrdinal,
    environment: AppEnvironment,
    exactMatchRequired: Boolean,
    balanceAdjustFunction: (SortedMap[Address, Balance], Set[BalanceAdjustment]) => Either[String, SortedMap[Address, Balance]]
  )

  val metagraphsBalancesAdjustments: Map[Address, List[BalanceAdjustmentAtOrdinal]] =
    BalanceAdjustmentLoader.loadAndCreateAdjustmentEntries(
      "/adjustments.json"
    ) match {
      case Right(adjustmentEntries) => adjustmentEntries
      case Left(error) =>
        throw new RuntimeException(s"Failed to load balance adjustments: $error")
    }

  /** Validates that the provided balance adjustments contain all required adjustments, then applies them to the current balances.
    */
  def applyAndValidateBalanceAdjustments(
    currentBalances: SortedMap[Address, Balance],
    balanceAdjustments: Set[BalanceAdjustment],
    requiredAdjustments: Set[RequiredAdjustment]
  ): Either[String, SortedMap[Address, Balance]] =
    for {
      _ <- validateRequiredAdjustments(balanceAdjustments, requiredAdjustments)
      updatedBalances <- applyBalanceAdjustments(currentBalances, balanceAdjustments)
    } yield updatedBalances

  /** Legacy validation retained for historical adjustment blocks. It checks that every required address/direction/amount tuple is present,
    * but deliberately ignores metadata and extras because tightening already-signed history can change replay results.
    */
  def validateRequiredAdjustments(
    balanceAdjustments: Set[BalanceAdjustment],
    requiredAdjustments: Set[RequiredAdjustment]
  ): Either[String, Unit] = {
    val missingAdjustments = requiredAdjustments.filterNot { required =>
      balanceAdjustments.exists { adjustment =>
        adjustment.address == required.address && matchesRequirement(adjustment, required.adjustment)
      }
    }

    if (missingAdjustments.nonEmpty) {
      val missingDescription = missingAdjustments.map(describeRequiredAdjustment).mkString(", ")
      Left(s"Missing required adjustments: $missingDescription")
    } else {
      Right(())
    }
  }

  /** Validates the complete artifact values for a newly authorized adjustment block, then applies them.
    *
    * Equality includes address, reason, reference set, increase and deduction. This prevents a metagraph from satisfying an authorized
    * deduction while attaching an unauthorized increase, repeating it with different metadata, or adding another artifact.
    */
  def applyAndValidateExactBalanceAdjustments(
    currentBalances: SortedMap[Address, Balance],
    balanceAdjustments: Set[BalanceAdjustment],
    authorizedAdjustments: Set[BalanceAdjustment]
  ): Either[String, SortedMap[Address, Balance]] =
    for {
      _ <- validateExactBalanceAdjustments(balanceAdjustments, authorizedAdjustments)
      updatedBalances <- applyBalanceAdjustments(currentBalances, balanceAdjustments)
    } yield updatedBalances

  def validateExactBalanceAdjustments(
    balanceAdjustments: Set[BalanceAdjustment],
    authorizedAdjustments: Set[BalanceAdjustment]
  ): Either[String, Unit] = {
    val missingAdjustments = authorizedAdjustments -- balanceAdjustments
    val unauthorizedAdjustments = balanceAdjustments -- authorizedAdjustments

    if (missingAdjustments.nonEmpty) {
      Left(s"Missing required adjustments: ${missingAdjustments.mkString(", ")}")
    } else if (unauthorizedAdjustments.nonEmpty) {
      Left(s"Unauthorized balance adjustments not present in the authorized set: ${unauthorizedAdjustments.mkString(", ")}")
    } else {
      Right(())
    }
  }

  def matchesRequirement(adjustment: BalanceAdjustment, required: AdjustmentType): Boolean =
    required match {
      case AdjustmentType.Increase(amount) => adjustment.increase.contains(amount)
      case AdjustmentType.Decrease(amount) => adjustment.deduct.contains(amount)
    }

  def describeRequiredAdjustment(required: RequiredAdjustment): String = {
    val typeStr = required.adjustment match {
      case AdjustmentType.Increase(amount) => s"increase $amount"
      case AdjustmentType.Decrease(amount) => s"decrease $amount"
    }
    s"$typeStr for ${required.address}"
  }

  /** Applies a set of balance adjustments to current balances. Processes increases first, then deductions to avoid potential ordering
    * issues.
    *
    * @param currentBalances
    *   Current balance state
    * @param balanceAdjustments
    *   Set of adjustments to apply
    * @return
    *   Either an error message or updated balances
    */
  def applyBalanceAdjustments(
    currentBalances: SortedMap[Address, Balance],
    balanceAdjustments: Set[BalanceAdjustment]
  ): Either[String, SortedMap[Address, Balance]] =
    for {
      afterIncreases <- applyIncreases(currentBalances, balanceAdjustments)
      afterDeductions <- applyDeductions(afterIncreases, balanceAdjustments)
    } yield afterDeductions

  private def applyIncreases(
    balances: SortedMap[Address, Balance],
    adjustments: Set[BalanceAdjustment]
  ): Either[String, SortedMap[Address, Balance]] =
    adjustments.toList.foldM(balances) { (acc, adjustment) =>
      adjustment.increase.fold(Right(acc): Either[String, SortedMap[Address, Balance]]) { increase =>
        val currentBalance = acc.getOrElse(adjustment.address, Balance.empty)
        currentBalance.plus(increase) match {
          case Left(error)       => Left(s"Failed to increase balance for ${adjustment.address}: $error")
          case Right(newBalance) => Right(acc.updated(adjustment.address, newBalance))
        }
      }
    }

  private def applyDeductions(
    balances: SortedMap[Address, Balance],
    adjustments: Set[BalanceAdjustment]
  ): Either[String, SortedMap[Address, Balance]] =
    adjustments.toList.foldM(balances) { (acc, adjustment) =>
      adjustment.deduct.fold(Right(acc): Either[String, SortedMap[Address, Balance]]) { deduction =>
        val currentBalance = acc.getOrElse(adjustment.address, Balance.empty)
        val difference = currentBalance.value.value - deduction.value.value

        val newBalance = if (difference < 0) {
          Balance.empty
        } else {
          currentBalance.minus(deduction).getOrElse(Balance.empty)
        }

        Right(acc.updated(adjustment.address, newBalance))
      }
    }
}
