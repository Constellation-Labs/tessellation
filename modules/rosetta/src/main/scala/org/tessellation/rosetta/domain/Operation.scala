package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.amount.Amount

import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import enumeratum.values.{StringCirceEnum, StringEnum, StringEnumEntry}
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object operation {
  @derive(customizableDecoder, customizableEncoder)
  case class Operation(
    operationIdentifier: OperationIdentifier,
    relatedOperations: Option[List[OperationIdentifier]],
    `type`: OperationType,
    status: Option[OperationStatus],
    account: Option[AccountIdentifier],
    amount: Option[Amount]
  )

  @derive(customizableDecoder, customizableEncoder)
  case class OperationIdentifier(
    index: OperationIndex
  )

  @derive(eqv, show)
  sealed abstract class OperationStatus(val value: String) extends StringEnumEntry

  object OperationStatus extends StringEnum[OperationStatus] with StringCirceEnum[OperationStatus] {
    val values = findValues

    case object Accepted extends OperationStatus(value = "Accepted")
    case object Pending extends OperationStatus(value = "Pending")
    case object Rejected extends OperationStatus(value = "Rejected")
    case object Unknown extends OperationStatus(value = "Unknown")
  }

  sealed abstract class OperationType(val value: String) extends StringEnumEntry
  object OperationType extends StringEnum[OperationType] with StringCirceEnum[OperationType] {
    val values = findValues

    case object Transfer extends OperationType(value = "Transfer")
  }

  @derive(decoder, encoder)
  @newtype
  case class OperationIndex(value: NonNegLong)
}
