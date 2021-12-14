package org.tessellation.dag.block

import cats.Applicative
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.validated._

import scala.util.control.NoStackTrace

import org.tessellation.dag.block.BlockValidator._
import org.tessellation.dag.transaction.TransactionValidator
import org.tessellation.dag.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.dag.transaction.filter.Consecutive
import org.tessellation.dag.types.{BlockReference, DAGBlock}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

abstract class BlockValidator[F[_]: Async: KryoSerializer: SecurityProvider](
  transactionValidator: TransactionValidator[F],
  requiredUniqueBlockSigners: PosInt
) {

  protected def areParentsAccepted(block: DAGBlock): F[Map[BlockReference, Boolean]]

  protected def getLastAcceptedTransactionRef(address: Address): F[TransactionReference]

  def validate(signedBlock: Signed[DAGBlock]): F[ValidationResult[Signed[DAGBlock]]] =
    for {
      signaturesValidation <- validateSignatures(signedBlock)
      signaturesCountValidation = validateSignaturesCount(signedBlock)
      parentsValidation <- validateParents(signedBlock)
      transactionsValidation <- validateTransactions(signedBlock)
    } yield
      signaturesValidation
        .product(signaturesCountValidation)
        .product(parentsValidation)
        .product(transactionsValidation)
        .map(_ => signedBlock)

  private def validateSignatures(signedBlock: Signed[DAGBlock]): F[ValidationResult[Signed[DAGBlock]]] =
    signedBlock.hasValidSignature
      .ifM(
        signedBlock.validNel.pure[F],
        Applicative[F].pure(InvalidBlockSignatures.invalidNel)
      )

  private def validateSignaturesCount(signedBlock: Signed[DAGBlock]): ValidationResult[Signed[DAGBlock]] = {
    val uniqueSignersCount = signedBlock.proofs.map(_.id).toList.toSet.size

    if (uniqueSignersCount >= requiredUniqueBlockSigners)
      signedBlock.validNel[BlockValidationError]
    else
      InvalidBlockSignaturesCount(uniqueSignersCount, requiredUniqueBlockSigners).invalidNel
  }

  private def validateParents(signedBlock: Signed[DAGBlock]): F[ValidationResult[Signed[DAGBlock]]] =
    areParentsAccepted(signedBlock.value).map { result =>
      val parents = signedBlock.value.parent.toList.toSet
      if (result.keySet == parents && result.values.forall(identity))
        signedBlock.validNel[BlockValidationError]
      else {
        val notAccepted = result.filterNot { case (_, isAccepted) => isAccepted }.keySet.intersect(parents)
        BlockParentsNotAccepted(notAccepted).invalidNel
      }
    }

  private def validateTransactions(
    signedBlock: Signed[DAGBlock]
  ): F[ValidationResult[Signed[DAGBlock]]] =
    NonEmptyList.fromList(signedBlock.value.transactions.toList) match {
      case Some(transactions) =>
        for {
          propertiesValidation <- validateTransactionsProperties(transactions)
          transactionChainsValidation <- validateTransactionChains(transactions)
        } yield propertiesValidation.product(transactionChainsValidation).map(_ => signedBlock)
      case None => signedBlock.validNel[BlockValidationError].pure[F]
    }

  private def validateTransactionsProperties(
    transactions: NonEmptyList[Signed[Transaction]]
  ): F[ValidationResult[NonEmptyList[Signed[Transaction]]]] =
    transactionValidator
      .validate(transactions)
      .map {
        case Valid(_)   => transactions.validNel[BlockValidationError]
        case Invalid(e) => InvalidBlockTransactions(e).invalidNel
      }

  private def validateTransactionChains(
    transactions: NonEmptyList[Signed[Transaction]]
  ): F[ValidationResult[NonEmptyList[Signed[Transaction]]]] =
    transactions.toList
      .groupBy(_.value.source)
      .toSeq
      .traverse {
        case (address, txs) =>
          for {
            lastAccepted <- getLastAcceptedTransactionRef(address)
            sorted = txs.sorted
            chainableTransactions = Consecutive.take(lastAccepted, txs)
            result = if (sorted == chainableTransactions)
              txs.validNel[BlockValidationError]
            else
              TransactionChainIncorrect(address, NonEmptyList(lastAccepted, sorted.map(_.value.parent))).invalidNel
          } yield result
      }
      .map(_.combineAll.map(_ => transactions))
}

object BlockValidator {
  type ValidationResult[A] = ValidatedNel[BlockValidationError, A]

  sealed trait BlockValidationError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  case object InvalidBlockSignatures extends BlockValidationError {
    override val errorMessage: String = "Block has invalid signatures!"
  }

  case class InvalidBlockSignaturesCount(actual: Int, shouldBe: PosInt) extends BlockValidationError {
    override val errorMessage: String = s"Block has invalid signatures number: $actual is less than $shouldBe!"
  }

  case class BlockParentsNotAccepted(notAcceptedParents: Set[BlockReference]) extends BlockValidationError {
    override val errorMessage: String =
      s"Some block's parents are not accepted! notAccepted: ${notAcceptedParents.show}"
  }

  case class InvalidBlockTransactions(transactionsErrors: NonEmptyList[TransactionValidationError])
      extends BlockValidationError {
    override val errorMessage: String = s"Block has invalid transactions! Transaction validation: $transactionsErrors"
  }

  case class TransactionChainIncorrect(address: Address, nonChainableTransactions: NonEmptyList[TransactionReference])
      extends BlockValidationError {
    override val errorMessage: String =
      s"Block has transactions that don't form a correct chain! Address=${address.show} transaction references=${nonChainableTransactions.show}"
  }
}
