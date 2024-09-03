package io.constellationnetwork.rosetta.domain

import cats.syntax.eq._

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.amount.Amount

import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import enumeratum.values.{StringCirceEnum, StringEnum, StringEnumEntry}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object operation {
  @derive(eqv, customizableDecoder, customizableEncoder, show)
  case class Operation(
    operationIdentifier: OperationIdentifier,
    relatedOperations: Option[List[OperationIdentifier]],
    `type`: OperationType,
    status: Option[OperationStatus],
    account: AccountIdentifier,
    amount: Amount
  )

  @derive(eqv, customizableDecoder, customizableEncoder, show)
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

    def isSuccessful(status: OperationStatus): Boolean =
      status === Accepted
  }

  @derive(eqv, show)
  sealed abstract class OperationType(val value: String) extends StringEnumEntry
  object OperationType extends StringEnum[OperationType] with StringCirceEnum[OperationType] {
    val values = findValues

    case object Transfer extends OperationType(value = "Transfer")
  }

  @derive(eqv, decoder, encoder, show)
  @newtype
  case class OperationIndex(value: NonNegLong)
}
