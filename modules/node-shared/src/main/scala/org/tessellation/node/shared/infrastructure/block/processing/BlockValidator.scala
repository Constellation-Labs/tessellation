package org.tessellation.node.shared.infrastructure.block.processing

import cats.Order
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.validated._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.transaction.TransactionChainValidator.TransactionNel
import org.tessellation.node.shared.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.schema.Block
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.signature.{Signed, SignedValidator}

import eu.timepit.refined.auto._

object BlockValidator {

  def make[F[_]: Async: KryoSerializer](
    signedValidator: SignedValidator[F],
    transactionChainValidator: TransactionChainValidator[F],
    transactionValidator: TransactionValidator[F]
  ): BlockValidator[F] =
    new BlockValidator[F] {

      def validate(
        signedBlock: Signed[Block],
        params: BlockValidationParams
      ): F[BlockValidationErrorOr[(Signed[Block], Map[Address, TransactionNel])]] =
        for {
          signedV <- validateSigned(signedBlock, params)
          transactionsV <- validateTransactions(signedBlock)
          propertiesV = validateProperties(signedBlock, params)
          transactionChainV <- validateTransactionChain(signedBlock)
        } yield
          signedV
            .productR(transactionsV)
            .productR(propertiesV)
            .product(transactionChainV)

      private def validateSigned(
        signedBlock: Signed[Block],
        params: BlockValidationParams
      ): F[BlockValidationErrorOr[Signed[Block]]] =
        signedValidator
          .validateSignatures(signedBlock)
          .map { signaturesV =>
            signaturesV
              .productR(signedValidator.validateUniqueSigners(signedBlock))
              .productR(signedValidator.validateMinSignatureCount(signedBlock, params.minSignatureCount))
          }
          .map(_.errorMap[BlockValidationError](InvalidSigned))

      private def validateTransactions(
        signedBlock: Signed[Block]
      ): F[BlockValidationErrorOr[Signed[Block]]] =
        signedBlock.value.transactions.toNonEmptyList.traverse { signedTransaction =>
          for {
            txRef <- TransactionReference.of(signedTransaction)
            txV <- transactionValidator.validate(signedTransaction)
          } yield txV.errorMap(InvalidTransaction(txRef, _))
        }.map { vs =>
          vs.foldLeft(signedBlock.validNec[BlockValidationError]) { (acc, v) =>
            acc.productL(v)
          }
        }

      private def validateTransactionChain(
        signedBlock: Signed[Block]
      ): F[BlockValidationErrorOr[Map[Address, TransactionNel]]] =
        transactionChainValidator
          .validate(signedBlock.transactions)
          .map(_.errorMap[BlockValidationError](InvalidTransactionChain))

      private def validateProperties(
        signedBlock: Signed[Block],
        params: BlockValidationParams
      ): BlockValidationErrorOr[Signed[Block]] =
        validateParentCount(signedBlock, params)
          .productR(validateUniqueParents(signedBlock))

      private def validateParentCount(
        signedBlock: Signed[Block],
        params: BlockValidationParams
      ): BlockValidationErrorOr[Signed[Block]] =
        if (signedBlock.parent.size >= params.minParentCount)
          signedBlock.validNec
        else
          NotEnoughParents(signedBlock.parent.size, params.minParentCount).invalidNec

      private def validateUniqueParents(
        signedBlock: Signed[Block]
      ): BlockValidationErrorOr[Signed[Block]] =
        duplicatedValues(signedBlock.parent).toNel
          .map(NonUniqueParents)
          .toInvalidNec(signedBlock)

      private def duplicatedValues[A: Order](values: NonEmptyList[A]): List[A] =
        values.groupBy(identity).toList.mapFilter {
          case (value, occurrences) =>
            if (occurrences.tail.nonEmpty)
              value.some
            else
              none
        }
    }
}
