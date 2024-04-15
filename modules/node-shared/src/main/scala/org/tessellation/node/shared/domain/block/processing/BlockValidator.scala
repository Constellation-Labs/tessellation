package org.tessellation.node.shared.domain.block.processing

import cats.Functor
import cats.data.{NonEmptyList, ValidatedNec}
import cats.syntax.functor._

import org.tessellation.node.shared.domain.transaction.TransactionChainValidator.{TransactionChainBroken, TransactionNel}
import org.tessellation.node.shared.domain.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.schema.{Block, BlockReference, SnapshotOrdinal}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.SignedValidator.SignedValidationError

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait BlockValidator[F[_]] {

  type BlockValidationErrorOr[A] = ValidatedNec[BlockValidationError, A]

  def validate(
    signedBlock: Signed[Block],
    snapshotOrdinal: SnapshotOrdinal,
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit hasher: Hasher[F]): F[BlockValidationErrorOr[(Signed[Block], Map[Address, TransactionNel])]]

  def validateGetBlock(
    signedBlock: Signed[Block],
    params: BlockValidationParams = BlockValidationParams.default,
    snapshotOrdinal: SnapshotOrdinal
  )(implicit ev: Functor[F], hasher: Hasher[F]): F[BlockValidationErrorOr[Signed[Block]]] =
    validate(signedBlock, snapshotOrdinal, params).map(_.map(_._1))

  def validateGetTxChains(
    signedBlock: Signed[Block],
    snapshotOrdinal: SnapshotOrdinal,
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F], hasher: Hasher[F]): F[BlockValidationErrorOr[Map[Address, TransactionNel]]] =
    validate(signedBlock, snapshotOrdinal, params).map(_.map(_._2))
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

case class AddressLockedAtOrdinal(address: Address, ordinal: SnapshotOrdinal, lockedAt: SnapshotOrdinal) extends BlockValidationError
