package org.tessellation.sdk.domain.block.processing

import cats.Functor
import cats.data.{NonEmptyList, ValidatedNec}
import cats.syntax.functor._

import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.schema.{Block, BlockReference}
import org.tessellation.sdk.domain.transaction.TransactionChainValidator.{TransactionChainBroken, TransactionNel}
import org.tessellation.sdk.domain.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.SignedValidator.SignedValidationError

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait BlockValidator[F[_], T <: Transaction, B <: Block[T]] {

  type BlockValidationErrorOr[A] = ValidatedNec[BlockValidationError, A]

  def validate(
    signedBlock: Signed[B],
    params: BlockValidationParams = BlockValidationParams.default
  ): F[BlockValidationErrorOr[(Signed[B], Map[Address, TransactionNel[T]])]]

  def validateGetBlock(
    signedBlock: Signed[B],
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F]): F[BlockValidationErrorOr[Signed[B]]] =
    validate(signedBlock, params).map(_.map(_._1))

  def validateGetTxChains(
    signedBlock: Signed[B],
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F]): F[BlockValidationErrorOr[Map[Address, TransactionNel[T]]]] =
    validate(signedBlock, params).map(_.map(_._2))
}

case class BlockValidationParams(minSignatureCount: PosInt, minParentCount: PosInt)

object BlockValidationParams {
  val default: BlockValidationParams = BlockValidationParams(minSignatureCount = 3, minParentCount = 2)
}

@derive(eqv, show)
sealed trait BlockValidationError

case class InvalidTransactionChain(error: TransactionChainBroken) extends BlockValidationError

case class InvalidTransaction(transactionReference: TransactionReference, error: TransactionValidationError) extends BlockValidationError

case class InvalidSigned(error: SignedValidationError) extends BlockValidationError

case class NotEnoughParents(parentCount: Int, minParentCount: Int) extends BlockValidationError

case class NonUniqueParents(duplicatedParents: NonEmptyList[BlockReference]) extends BlockValidationError
