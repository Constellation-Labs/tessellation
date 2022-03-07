package org.tessellation.dag.transaction

import cats.Applicative
import cats.data.{NonEmptyList, ValidatedNel}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.validated._

import scala.util.control.NoStackTrace

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

import eu.timepit.refined.auto._
import io.estatico.newtype.ops._

abstract class TransactionValidator[F[_]: Async: KryoSerializer: SecurityProvider] {
  import TransactionValidator._

  protected def getBalance(address: Address): F[Balance]

  protected def getLastAcceptedTransactionRef(address: Address): F[TransactionReference]

  def validate(signedTransaction: Signed[Transaction]): F[ValidationResult[Signed[Transaction]]] =
    validate(NonEmptyList.one(signedTransaction))
      .map(_.map(_.head))

  def validate(
    signedTransactions: NonEmptyList[Signed[Transaction]]
  ): F[ValidationResult[NonEmptyList[Signed[Transaction]]]] =
    for {
      propertiesValidation <- signedTransactions.traverse(validateTransactionProperties).map(_.sequence)
      balancesValidation <- validateSourceBalances(signedTransactions.map(_.value))
    } yield propertiesValidation.product(balancesValidation).map(_ => signedTransactions)

  private def validateTransactionProperties(
    signedTransaction: Signed[Transaction]
  ): F[ValidationResult[Signed[Transaction]]] =
    for {
      staticValidation <- Async[F].delay(
        validateAddressInequality(signedTransaction.value)
      )
      signatureValidation <- validateSourceSignature(signedTransaction)
      sourceValidation <- validateSourceAddress(signedTransaction)
      lastTxRefValidation <- validateLastTransactionRef(signedTransaction.value)
    } yield
      staticValidation
        .product(signatureValidation)
        .product(sourceValidation)
        .product(lastTxRefValidation)
        .map(_ => signedTransaction)

  private def validateLastTransactionRef(tx: Transaction): F[ValidationResult[Transaction]] =
    getLastAcceptedTransactionRef(tx.source).map { lastTxRef =>
      lazy val comparedOrdinals = tx.parent.ordinal.compare(lastTxRef.ordinal)
      lazy val nonZeroOrdinalHasEmptyHash = tx.parent.ordinal.coerce > 0L && tx.parent.hash.coerce.isEmpty
      lazy val ordinalIsLower = comparedOrdinals < 0L
      lazy val hashIsNotEqual = comparedOrdinals == 0L && tx.parent != lastTxRef

      if (nonZeroOrdinalHasEmptyHash)
        NonZeroOrdinalButEmptyHash(tx).invalidNel
      else if (ordinalIsLower)
        ParentTxRefOrdinalLowerThenStoredLastTxRef(tx).invalidNel
      else if (hashIsNotEqual)
        SameOrdinalButDifferentHashForLastTxRef(tx).invalidNel
      else
        tx.validNel
    }

  private def validateSourceAddress(signedTransaction: Signed[Transaction]): F[ValidationResult[Transaction]] =
    signedTransaction.proofs.existsM {
      case SignatureProof(id, _) =>
        for {
          publicKey <- id.hex.toPublicKey
          address = publicKey.toAddress
        } yield address != signedTransaction.value.source
    }.ifM(
      Applicative[F].pure(SourceAddressAndSignerIdsDontMatch(signedTransaction.value).invalidNel),
      signedTransaction.value.validNel.pure[F]
    )

  private def validateSourceSignature(signedTransaction: Signed[Transaction]): F[ValidationResult[Transaction]] =
    signedTransaction.hasValidSignature.ifM(
      signedTransaction.value.validNel.pure[F],
      Applicative[F].pure(InvalidSourceSignature.invalidNel)
    )

  private def validateSourceBalances(
    transactions: NonEmptyList[Transaction]
  ): F[ValidationResult[NonEmptyList[Transaction]]] = {
    def validate(source: Address, txs: Seq[Transaction]): F[ValidationResult[Seq[Transaction]]] =
      getBalance(source).map { balance =>
        txs
          .foldLeft(balance.some) {
            case (b, tx) => b.flatMap(_.minus(tx.amount).toOption).flatMap(_.minus(tx.fee).toOption)
          }
          .isDefined
      }.ifM(
        txs.validNel.pure[F],
        Applicative[F].pure(InsufficientSourceBalance(source).invalidNel)
      )

    transactions.toList
      .groupBy(_.source)
      .toSeq
      .traverse { case (source, txs) => validate(source, txs) }
      .map(_.combineAll)
      .map(_.map(_ => transactions))
  }

  private def validateAddressInequality(tx: Transaction): ValidationResult[Transaction] =
    if (tx.source != tx.destination)
      tx.validNel
    else
      SourceAndDestinationAddressAreEqual(tx).invalidNel
}

object TransactionValidator {

  type ValidationResult[A] = ValidatedNel[TransactionValidationError, A]

  sealed trait TransactionValidationError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  sealed trait IncorrectParentReference extends TransactionValidationError {
    val parentReference: TransactionReference

    val errorMessage: String =
      s"Transaction has an incorrect parent reference: ${parentReference.show}"
  }

  case class SourceAndDestinationAddressAreEqual(source: Address, destination: Address)
      extends TransactionValidationError {

    val errorMessage: String =
      s"Transaction's source and destination address are equal. Source=${source.show} destination=${destination.show}"
  }

  object SourceAndDestinationAddressAreEqual {

    def apply(tx: Transaction) =
      new SourceAndDestinationAddressAreEqual(
        tx.source,
        tx.destination
      )
  }

  case class ParentTxRefOrdinalLowerThenStoredLastTxRef(parentReference: TransactionReference)
      extends IncorrectParentReference

  object ParentTxRefOrdinalLowerThenStoredLastTxRef {
    def apply(tx: Transaction) = new ParentTxRefOrdinalLowerThenStoredLastTxRef(tx.parent)
  }

  case class SameOrdinalButDifferentHashForLastTxRef(parentReference: TransactionReference)
      extends IncorrectParentReference

  object SameOrdinalButDifferentHashForLastTxRef {
    def apply(tx: Transaction) = new SameOrdinalButDifferentHashForLastTxRef(tx.parent)
  }

  case class NonZeroOrdinalButEmptyHash(parentReference: TransactionReference) extends IncorrectParentReference

  object NonZeroOrdinalButEmptyHash {
    def apply(tx: Transaction) = new NonZeroOrdinalButEmptyHash(tx.parent)
  }

  case class SourceAddressAndSignerIdsDontMatch(source: Address) extends TransactionValidationError {
    val errorMessage: String = s"Transactions source address=${source.show} doesn't match the signing ids"
  }

  object SourceAddressAndSignerIdsDontMatch {
    def apply(tx: Transaction) = new SourceAddressAndSignerIdsDontMatch(tx.source)
  }

  case class InsufficientSourceBalance(source: Address) extends TransactionValidationError {
    val errorMessage: String = s"Transactions source address=${source.show} has insufficient balance"
  }

  case object InvalidSourceSignature extends TransactionValidationError {
    val errorMessage: String = s"Transaction has invalid source signature"
  }
}
