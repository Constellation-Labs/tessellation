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
import cats.syntax.partialOrder._
import cats.syntax.validated._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.transaction.TransactionChainValidator.TransactionNel
import org.tessellation.node.shared.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.{Signed, SignedValidator}

import eu.timepit.refined.auto._

object BlockValidator {

  def make[F[_]: Async](
    signedValidator: SignedValidator[F],
    transactionChainValidator: TransactionChainValidator[F],
    transactionValidator: TransactionValidator[F],
    txHasher: Hasher[F]
  ): BlockValidator[F] =
    new BlockValidator[F] {

      def validate(
        signedBlock: Signed[Block],
        snapshotOrdinal: SnapshotOrdinal,
        params: BlockValidationParams
      )(implicit hasher: Hasher[F]): F[BlockValidationErrorOr[(Signed[Block], Map[Address, TransactionNel])]] =
        for {
          signedV <- validateSigned(signedBlock, params)(hasher)
          lockedV = validateNotLockedAtOrdinal(signedBlock, snapshotOrdinal)
          transactionsV <- validateTransactions(signedBlock)
          propertiesV = validateProperties(signedBlock, params)
          transactionChainV <- validateTransactionChain(signedBlock)
        } yield
          signedV
            .productR(lockedV)
            .productR(transactionsV)
            .productR(propertiesV)
            .product(transactionChainV)

      private def validateSigned(
        signedBlock: Signed[Block],
        params: BlockValidationParams
      )(implicit hasher: Hasher[F]): F[BlockValidationErrorOr[Signed[Block]]] =
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
      ): F[BlockValidationErrorOr[Signed[Block]]] = {
        implicit val hasher = txHasher

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

      private val addressesLockedAtOrdinal: Map[Address, SnapshotOrdinal] = Map(
        Address("DAG6LvxLSdWoC9uJZPgXtcmkcWBaGYypF6smaPyH") -> SnapshotOrdinal(1447110L) // NOTE: BitForex
      )

      private def validateNotLockedAtOrdinal(signedBlock: Signed[Block], ordinal: SnapshotOrdinal): BlockValidationErrorOr[Signed[Block]] =
        signedBlock.value.transactions.toNonEmptyList
          .map(signedTxn =>
            addressesLockedAtOrdinal
              .get(signedTxn.value.source)
              .filter(lockedAt => ordinal >= lockedAt)
              .fold(signedBlock.validNec[BlockValidationError])(
                AddressLockedAtOrdinal(signedTxn.value.source, ordinal, _).invalidNec[Signed[Block]]
              )
          )
          .reduce[BlockValidationErrorOr[Signed[Block]]](_ *> _)

    }
}
