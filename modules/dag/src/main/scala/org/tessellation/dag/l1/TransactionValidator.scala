package org.tessellation.dag.l1

import cats.data.ValidatedNel
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.syntax.validated._

import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.storage.TransactionStorage
import org.tessellation.domain.cluster.storage.AddressStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

import eu.timepit.refined.auto._
import io.estatico.newtype.ops._

import TransactionValidator._

class TransactionValidator[F[_]: Async: KryoSerializer: SecurityProvider](
  transactionStorage: TransactionStorage[F],
  addressStorage: AddressStorage[F]
) {
//  def validateDuplicate(tx: Transaction): F[ValidationResult[Transaction]] =
//    transactionService.isAccepted(tx.baseHash).map { isAccepted =>
//      if (isAccepted)
//        HashDuplicateFound(tx).invalidNel
//      else
//        tx.validNel
//    }

  def validateSingleTransaction(signedTransaction: Signed[Transaction]): F[ValidationResult[Signed[Transaction]]] =
    for {
      propertiesValidation <- validateTransactionProperties(signedTransaction)
      balanceValidation <- validateSourceBalances(Seq(signedTransaction.value))
    } yield propertiesValidation.product(balanceValidation).map(_ => signedTransaction)

  def validateBatchOfTransactions(
    signedTransactions: Seq[Signed[Transaction]]
  ): F[ValidationResult[Seq[Signed[Transaction]]]] =
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
        // THESE ARE VALIDATED BY REFINED TYPES
        //.product(validateEmptyDestinationAddress(tx))
        //.product(validateDestinationAddress(tx))
        //.product(validateAmount(tx))
        //.product(validateFee(tx))
      )
      signatureValidation <- validateSourceSignature(signedTransaction)
      sourceValidation <- validateSourceAddress(signedTransaction)
      // THE IDEA IS THAT WE WON'T STORE TRANSACTIONS OTHER THEN THE LAST ACCEPTED, SO NO DUPLICATION POSSIBLE, IT'S ENOUGH TO CHECK LAST TRANSACTION AND IF IT'S THE SAME
      //duplicateValidation <- validateDuplicate(transaction)
      lastTxRefValidation <- validateLastTransactionRef(signedTransaction.value)
    } yield
      staticValidation /*.product(duplicateValidation)*/
        .product(signatureValidation)
        .product(sourceValidation)
        .product(lastTxRefValidation)
        .map(_ => signedTransaction)

  private def validateLastTransactionRef(tx: Transaction): F[ValidationResult[Transaction]] =
    transactionStorage
      .getLastAcceptedTransactionRef(tx.source)
      .map { lastTxRef =>
        lazy val comparedOrdinals = tx.parent.ordinal.coerce.compare(lastTxRef.ordinal.coerce)
        lazy val nonZeroOrdinalHasEmptyHash = tx.parent.ordinal.coerce > 0L && tx.parent.hash.coerce.isEmpty
        lazy val ordinalIsLower = comparedOrdinals < 0L
        lazy val hashIsNotEqual = comparedOrdinals == 0L && tx.parent != lastTxRef

        if (nonZeroOrdinalHasEmptyHash)
          NonZeroOrdinalButEmptyHash(tx).invalidNel
        else if (ordinalIsLower)
          LastTxRefOrdinalLowerThenStoredLastTxRef(tx).invalidNel
        else if (hashIsNotEqual)
          SameOrdinalButDifferentHashForLastTxRef(tx).invalidNel
        else
          tx.validNel
      }

  private def validateSourceAddress(signedTransaction: Signed[Transaction]): F[ValidationResult[Transaction]] =
    signedTransaction.proofs.traverse {
      case SignatureProof(id, _) => {
        for {
          publicKey <- id.hex.toPublicKey
          address = publicKey.toAddress
        } yield address == signedTransaction.value.source
      }.handleError(_ => false)
    }.map(_.forall(identity))
      .ifM(
        Async[F].pure(signedTransaction.value.validNel),
        Async[F].pure(SourceAddressAndSignerIdsDontMatch(signedTransaction.value).invalidNel)
      )

  private def validateSourceSignature(signedTransaction: Signed[Transaction]): F[ValidationResult[Transaction]] =
    signedTransaction.hasValidSignature.ifM(
      Async[F].pure(signedTransaction.value.validNel),
      Async[F].pure(InvalidSourceSignature.invalidNel)
    )

  private def validateSourceBalances(transactions: Seq[Transaction]): F[ValidationResult[Seq[Transaction]]] = {
    def validate(source: Address, txs: Seq[Transaction]): F[ValidationResult[Seq[Transaction]]] =
      addressStorage
        .getBalance(source)
        .map { balance =>
          val totalAmount = txs.map(tx => tx.amount.coerce + tx.fee.coerce).sum
          val remaining = balance.coerce - totalAmount

          remaining >= 0
        }
        .ifM(
          Async[F].pure(txs.validNel),
          Async[F].pure(InsufficientSourceBalance(source).invalidNel)
        )

    transactions
      .groupBy(_.source)
      .toSeq
      //TODO: why can't I just do: .map(validate)???
      .traverse { case (source, txs) => validate(source, txs) }
      .map(_.combineAll)
  }
}

object TransactionValidator {

  type ValidationResult[A] = ValidatedNel[TransactionValidationError, A]

  private def validateAddressInequality(tx: Transaction): ValidationResult[Transaction] =
    if (tx.source != tx.destination)
      tx.validNel
    else
      SourceAndDestinationAddressAreEqual(tx).invalidNel

  sealed trait TransactionValidationError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  sealed trait IncorrectParentReference extends TransactionValidationError {
    val parentReference: TransactionReference

    val errorMessage: String =
      s"Transaction has an incorrect last transaction reference: $parentReference."
  }

  case class SourceAndDestinationAddressAreEqual(source: Address, destination: Address)
      extends TransactionValidationError {

    val errorMessage: String =
      s"Transaction's source and destination address are equal. Source=$source destination=$destination"
  }

  case class InconsistentParentReference(parentReference: TransactionReference) extends IncorrectParentReference

  case class LastTxRefOrdinalLowerThenStoredLastTxRef(parentReference: TransactionReference)
      extends IncorrectParentReference

  case class SameOrdinalButDifferentHashForLastTxRef(parentReference: TransactionReference)
      extends IncorrectParentReference

  case class NonZeroOrdinalButEmptyHash(parentReference: TransactionReference) extends IncorrectParentReference

  case class SourceAddressAndSignerIdsDontMatch(source: Address) extends TransactionValidationError {
    val errorMessage: String = s"Transactions source address=$source doesn't match the signing ids"
  }

  case class InsufficientSourceBalance(source: Address) extends TransactionValidationError {
    val errorMessage: String = s"Transactions source address=$source has insufficient balance"
  }

  case object InvalidSourceSignature extends TransactionValidationError {
    val errorMessage: String = s"Transaction has invalid source signature"
  }

  object SourceAddressAndSignerIdsDontMatch {
    def apply(tx: Transaction) = new SourceAddressAndSignerIdsDontMatch(tx.source)
  }

  object SourceAndDestinationAddressAreEqual {

    def apply(tx: Transaction) =
      new SourceAndDestinationAddressAreEqual(
        tx.source,
        tx.destination
      )
  }

  object InconsistentParentReference {
    def apply(tx: Transaction) = new InconsistentParentReference(tx.parent)
  }

  object LastTxRefOrdinalLowerThenStoredLastTxRef {
    def apply(tx: Transaction) = new LastTxRefOrdinalLowerThenStoredLastTxRef(tx.parent)
  }

  object SameOrdinalButDifferentHashForLastTxRef {
    def apply(tx: Transaction) = new SameOrdinalButDifferentHashForLastTxRef(tx.parent)
  }

  object NonZeroOrdinalButEmptyHash {
    def apply(tx: Transaction) = new NonZeroOrdinalButEmptyHash(tx.parent)
  }

}
