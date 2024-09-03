package io.constellationnetwork.rosetta.domain

import cats.syntax.option._

import io.constellationnetwork.generators.nesGen
import io.constellationnetwork.rosetta.domain.amount._
import io.constellationnetwork.rosetta.domain.api.construction.ConstructionMetadata.MetadataResult
import io.constellationnetwork.rosetta.domain.currency.DAG
import io.constellationnetwork.rosetta.domain.operation._
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.scalacheck.NumericInstances
import eu.timepit.refined.types.numeric.PosLong
import org.scalacheck.{Arbitrary, Gen}

object generators extends NumericInstances {

  val subAccountIdentifierGen: Gen[SubAccountIdentifier] = addressGen.map(a => SubAccountIdentifier(a))

  val accountIdentifierGen: Gen[AccountIdentifier] = for {
    address <- addressGen
    subAcctId <- Gen.option(subAccountIdentifierGen)
  } yield AccountIdentifier(address, subAcctId)

  val positiveAmountGen: Gen[Amount] = for {
    posLong <- Arbitrary.arbitrary[PosLong].map(_.value)
    posAmount = Amount(AmountValue(Refined.unsafeApply[Long, AmountValuePredicate](posLong)), DAG)
  } yield posAmount

  val payloadOperationsGen: Gen[(Operation, Operation)] =
    for {
      posAcctId <- accountIdentifierGen
      negAcctId <- accountIdentifierGen
      posAmount <- positiveAmountGen
      negAmount = posAmount.negate
    } yield
      (
        Operation(OperationIdentifier(OperationIndex(0L)), none, OperationType.Transfer, none, negAcctId, negAmount),
        Operation(OperationIdentifier(OperationIndex(1L)), none, OperationType.Transfer, none, posAcctId, posAmount)
      )

  val metadataResultGen: Gen[MetadataResult] =
    for {
      lastRef <- transactionReferenceGen
      fee <- Gen.option(positiveAmountGen)
    } yield MetadataResult(lastRef, fee)

  val rosettaPublicKeyGen: Gen[RosettaPublicKey] =
    nesGen(nes => RosettaPublicKey(Hex(nes), curveType = CurveType.SECP256K1))
}
