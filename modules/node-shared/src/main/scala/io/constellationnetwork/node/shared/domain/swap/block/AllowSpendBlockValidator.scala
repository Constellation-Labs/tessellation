package io.constellationnetwork.node.shared.domain.swap.block

import cats.Functor
import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.partialOrder._
import cats.syntax.validated._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.swap.AllowSpendChainValidator.{AllowSpendChainBroken, AllowSpendNel}
import io.constellationnetwork.node.shared.domain.swap.AllowSpendValidator.AllowSpendValidationError
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendChainValidator, AllowSpendValidator}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{AllowSpendBlock, AllowSpendReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait AllowSpendBlockValidator[F[_]] {

  type AllowSpendBlockValidationErrorOr[A] = ValidatedNec[AllowSpendBlockValidationError, A]

  def validate(
    signedBlock: Signed[AllowSpendBlock],
    snapshotOrdinal: SnapshotOrdinal,
    params: AllowSpendBlockValidationParams = AllowSpendBlockValidationParams.default
  )(implicit hasher: Hasher[F]): F[AllowSpendBlockValidationErrorOr[(Signed[AllowSpendBlock], Map[Address, AllowSpendNel])]]

  def validateGetBlock(
    signedBlock: Signed[AllowSpendBlock],
    params: AllowSpendBlockValidationParams = AllowSpendBlockValidationParams.default,
    snapshotOrdinal: SnapshotOrdinal
  )(implicit ev: Functor[F], hasher: Hasher[F]): F[AllowSpendBlockValidationErrorOr[Signed[AllowSpendBlock]]] =
    validate(signedBlock, snapshotOrdinal, params).map(_.map(_._1))

  def validateGetTxChains(
    signedBlock: Signed[AllowSpendBlock],
    snapshotOrdinal: SnapshotOrdinal,
    params: AllowSpendBlockValidationParams = AllowSpendBlockValidationParams.default
  )(implicit ev: Functor[F], hasher: Hasher[F]): F[AllowSpendBlockValidationErrorOr[Map[Address, AllowSpendNel]]] =
    validate(signedBlock, snapshotOrdinal, params).map(_.map(_._2))
}

object AllowSpendBlockValidator {

  def make[F[_]: Async](
    signedValidator: SignedValidator[F],
    allowSpendChainValidator: AllowSpendChainValidator[F],
    allowSpendValidator: AllowSpendValidator[F]
  ): AllowSpendBlockValidator[F] =
    new AllowSpendBlockValidator[F] {

      def validate(
        signedBlock: Signed[AllowSpendBlock],
        snapshotOrdinal: SnapshotOrdinal,
        params: AllowSpendBlockValidationParams
      )(implicit hasher: Hasher[F]): F[AllowSpendBlockValidationErrorOr[(Signed[AllowSpendBlock], Map[Address, AllowSpendNel])]] =
        for {
          signedV <- validateSigned(signedBlock, params)(hasher)
          lockedV = validateNotLockedAtOrdinal(signedBlock, snapshotOrdinal)
          transactionsV <- validateAllowSpends(signedBlock)
          transactionChainV <- validateAllowSpendChain(signedBlock)
        } yield
          signedV
            .productR(lockedV)
            .productR(transactionsV)
            .product(transactionChainV)

      private def validateSigned(
        signedBlock: Signed[AllowSpendBlock],
        params: AllowSpendBlockValidationParams
      )(implicit hasher: Hasher[F]): F[AllowSpendBlockValidationErrorOr[Signed[AllowSpendBlock]]] =
        signedValidator
          .validateSignatures(signedBlock)
          .map { signaturesV =>
            signaturesV
              .productR(signedValidator.validateUniqueSigners(signedBlock))
              .productR(signedValidator.validateMinSignatureCount(signedBlock, params.minSignatureCount))
          }
          .map(_.errorMap[AllowSpendBlockValidationError](InvalidSigned))

      private def validateAllowSpends(
        signedBlock: Signed[AllowSpendBlock]
      )(implicit hasher: Hasher[F]): F[AllowSpendBlockValidationErrorOr[Signed[AllowSpendBlock]]] =
        signedBlock.value.transactions.toNonEmptyList.traverse { signedTransaction =>
          for {
            txRef <- AllowSpendReference.of(signedTransaction)
            txV <- allowSpendValidator.validate(signedTransaction)
          } yield txV.errorMap(InvalidAllowSpend(txRef, _))
        }.map { vs =>
          vs.foldLeft(signedBlock.validNec[AllowSpendBlockValidationError]) { (acc, v) =>
            acc.productL(v)
          }
        }

      private def validateAllowSpendChain(
        signedBlock: Signed[AllowSpendBlock]
      ): F[AllowSpendBlockValidationErrorOr[Map[Address, AllowSpendNel]]] =
        allowSpendChainValidator
          .validate(signedBlock.transactions)
          .map(_.errorMap[AllowSpendBlockValidationError](InvalidAllowSpendChain))

      private val addressesLockedAtOrdinal: Map[Address, SnapshotOrdinal] = Map.empty

      private def validateNotLockedAtOrdinal(
        signedBlock: Signed[AllowSpendBlock],
        ordinal: SnapshotOrdinal
      ): AllowSpendBlockValidationErrorOr[Signed[AllowSpendBlock]] =
        signedBlock.value.transactions.toNonEmptyList
          .map(signedTxn =>
            addressesLockedAtOrdinal
              .get(signedTxn.value.source)
              .filter(lockedAt => ordinal >= lockedAt)
              .fold(signedBlock.validNec[AllowSpendBlockValidationError])(
                AddressLockedAtOrdinal(signedTxn.value.source, ordinal, _).invalidNec[Signed[AllowSpendBlock]]
              )
          )
          .reduce[AllowSpendBlockValidationErrorOr[Signed[AllowSpendBlock]]](_ *> _)

    }
}

case class AllowSpendBlockValidationParams(minSignatureCount: PosInt)

object AllowSpendBlockValidationParams {
  val default: AllowSpendBlockValidationParams = AllowSpendBlockValidationParams(minSignatureCount = 3)
}

@derive(eqv, show)
sealed trait AllowSpendBlockValidationError

case class InvalidAllowSpendChain(error: AllowSpendChainBroken) extends AllowSpendBlockValidationError

case class InvalidAllowSpend(allowSpendReference: AllowSpendReference, error: AllowSpendValidationError)
    extends AllowSpendBlockValidationError

case class InvalidSigned(error: SignedValidationError) extends AllowSpendBlockValidationError

case class AddressLockedAtOrdinal(address: Address, ordinal: SnapshotOrdinal, lockedAt: SnapshotOrdinal)
    extends AllowSpendBlockValidationError
