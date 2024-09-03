package io.constellationnetwork.rosetta.domain.networkapi.model

import cats.syntax.option._

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.currency.Currency
import io.constellationnetwork.rosetta.domain.error.{RosettaError, RosettaErrorJson}
import io.constellationnetwork.rosetta.domain.operation.OperationStatus
import io.constellationnetwork.rosetta.domain.operation.OperationType.Transfer
import io.constellationnetwork.schema.address.Address

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.customizableEncoder
import derevo.derive
import enumeratum.values.{StringCirceEnum, StringEnum, StringEnumEntry}

object options {
  @derive(customizableEncoder, eqv, show)
  case class Version(rosettaVersion: String, nodeVersion: String)

  @derive(customizableEncoder, eqv, show)
  case class AllowedOperationStatus(status: OperationStatus, successful: Boolean)
  object AllowedOperationStatus {
    def apply(opStatus: OperationStatus): AllowedOperationStatus =
      AllowedOperationStatus(opStatus, OperationStatus.isSuccessful(opStatus))

    def allStatuses: List[AllowedOperationStatus] =
      OperationStatus.values.map(AllowedOperationStatus(_)).toList
  }

  @derive(eqv, show)
  sealed abstract class Case(val value: String) extends StringEnumEntry

  object Case extends StringEnum[Case] with StringCirceEnum[Case] {
    val values = findValues

    case object CaseSensitive extends Case(value = "case_sensitive")
  }

  @derive(eqv, show)
  sealed abstract class ExemptionType(val value: String) extends StringEnumEntry

  object ExemptionType extends StringEnum[ExemptionType] with StringCirceEnum[ExemptionType] {
    val values = findValues

    case object GreaterOrEqual extends ExemptionType(value = "greater_or_equal")
    case object LessOrEqual extends ExemptionType(value = "less_or_equal")
    case object Dynamic extends ExemptionType(value = "dynamic")
  }

  @derive(customizableEncoder, eqv, show)
  case class BalanceExemption(subAccountAddress: Address, currency: Currency, exemptionType: ExemptionType)

  @derive(customizableEncoder, eqv, show)
  case class Allow(
    operationStatuses: List[AllowedOperationStatus],
    operationTypes: List[String],
    errors: List[RosettaErrorJson],
    historicalBalanceLookup: Boolean,
    timestampStartIndex: Option[Long],
    callMethods: List[String],
    balanceExemptions: List[BalanceExemption],
    mempoolCoins: Boolean,
    blockHashCase: Option[Case],
    transactionHashCase: Option[Case]
  )

  object Allow {
    val default: Allow = Allow(
      operationStatuses = AllowedOperationStatus.allStatuses,
      operationTypes = List(Transfer.toString),
      errors = RosettaError.values.map(RosettaErrorJson(_)).toList,
      historicalBalanceLookup = false,
      timestampStartIndex = none, // TODO this will become a hard-coded constant
      callMethods = Nil,
      balanceExemptions = Nil,
      mempoolCoins = false,
      blockHashCase = Case.CaseSensitive.some,
      transactionHashCase = Case.CaseSensitive.some
    )
  }

  @derive(customizableEncoder, eqv, show)
  case class NetworkApiOptions(version: Version, allow: Allow)
}
