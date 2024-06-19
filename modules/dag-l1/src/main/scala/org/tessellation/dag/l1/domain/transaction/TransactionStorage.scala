package org.tessellation.dag.l1.domain.transaction

import cats._
import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.transaction.TransactionStorage._
import org.tessellation.dag.l1.domain.transaction.{ContextualAllowSpendValidator, ContextualTransactionValidator}
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.swap._
import org.tessellation.schema.transaction._
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

import StoredTransaction.{AcceptedTx, MajorityTx, ProcessingTx, WaitingTx}
import StoredAllowSpend.{AcceptedAllowSpend, MajorityAllowSpend, ProcessingAllowSpend, WaitingAllowSpend}

class TransactionStorage[F[_]: Async](
  transactionsR: MapRef[F, Address, Option[SortedMap[TransactionOrdinal, StoredTransaction]]],
  allowSpendsR: MapRef[F, Address, Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]]],
  initialTransactionReference: TransactionReference,
  initialAllowSpendReference: AllowSpendReference,
  contextualTransactionValidator: ContextualTransactionValidator,
  contextualAllowSpendValidator: ContextualAllowSpendValidator
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val transactionLogger = Slf4jLogger.getLoggerFromName[F](transactionLoggerName)

  def getInitialTx: MajorityTx = MajorityTx(initialTransactionReference, SnapshotOrdinal.MinValue)

  def getInitialAllowSpend: MajorityAllowSpend = MajorityAllowSpend(initialAllowSpendReference, SnapshotOrdinal.MinValue)

  def getState
    : F[(Map[Address, SortedMap[TransactionOrdinal, StoredTransaction]], Map[Address, SortedMap[AllowSpendOrdinal, StoredAllowSpend]])] =
    transactionsR.toMap.flatMap(txs => allowSpendsR.toMap.map((txs, _)))

  def getLastProcessedTransaction(source: Address): F[StoredTransaction] =
    transactionsR(source).get.map {
      _.flatMap(getLastProcessedTransaction).getOrElse(getInitialTx)
    }

  def getLastProcessedAllowSpend(source: Address): F[StoredAllowSpend] =
    allowSpendsR(source).get.map {
      _.flatMap(getLastProcessedAllowSpend).getOrElse(getInitialAllowSpend)
    }

  def getActiveAllowSpends: F[Option[List[Hashed[AllowSpend]]]] = ???

  private def getLastProcessedTransaction(stored: SortedMap[TransactionOrdinal, StoredTransaction]): Option[StoredTransaction] =
    stored.collect { case tx @ (_, _: AcceptedTx | _: MajorityTx) => tx }.lastOption.map { case (_, transaction) => transaction }

  private def getLastProcessedAllowSpend(stored: SortedMap[AllowSpendOrdinal, StoredAllowSpend]): Option[StoredAllowSpend] =
    stored.collect { case tx @ (_, _: AcceptedAllowSpend | _: MajorityAllowSpend) => tx }.lastOption.map {
      case (_, transaction) => transaction
    }

  def initByRefs(refs: Map[Address, TransactionReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse {
      case (address, reference) =>
        transactionsR(address)
          .modify[Either[Error, Unit]] {
            case curr @ Some(_) =>
              (curr, new Error("Storage should be empty before download").asLeft)
            case None =>
              val initial = SortedMap(reference.ordinal -> MajorityTx(reference, snapshotOrdinal))
              (initial.some, ().asRight)
          }
          .flatMap(_.liftTo[F])
    }.void

  def initAllowSpendByRefs(refs: Map[Address, AllowSpendReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse {
      case (address, reference) =>
        allowSpendsR(address)
          .modify[Either[Error, Unit]] {
            case curr @ Some(_) =>
              (curr, new Error("Storage should be empty before download").asLeft)
            case None =>
              val initial = SortedMap(reference.ordinal -> MajorityAllowSpend(reference, snapshotOrdinal))
              (initial.some, ().asRight)
          }
          .flatMap(_.liftTo[F])
    }.void

  def replaceByRefs(refs: Map[Address, TransactionReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (address, reference) =>
        val initial = SortedMap(reference.ordinal -> MajorityTx(reference, snapshotOrdinal))
        transactionsR(address).set(initial.some)
    }

  def replaceAllowSpendByRefs(refs: Map[Address, AllowSpendReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (address, reference) =>
        val initial = SortedMap(reference.ordinal -> MajorityAllowSpend(reference, snapshotOrdinal))
        allowSpendsR(address).set(initial.some)
    }

  def advanceMajorityRefs(refs: Map[Address, TransactionReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (source, majorityTxRef) =>
        transactionsR(source).modify[Either[MarkingTransactionReferenceAsMajorityError, Unit]] { maybeStored =>
          val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])

          stored.collectFirst { case (_, a @ AcceptedTx(tx)) if a.ref === majorityTxRef => tx }.map { majorityTx =>
            val remaining = stored.filter { case (ordinal, _) => ordinal > majorityTx.ordinal }

            remaining + (majorityTx.ordinal -> MajorityTx(TransactionReference.of(majorityTx), snapshotOrdinal))
          } match {
            case Some(updated) => (updated.some, ().asRight)
            case None          => (maybeStored, UnexpectedStateWhenMarkingTxRefAsMajority(source, majorityTxRef, None).asLeft)
          }
        }
    }

  def advanceAllowSpendMajorityRefs(refs: Map[Address, AllowSpendReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (source, majorityTxRef) =>
        allowSpendsR(source).modify[Either[MarkingAllowSpendReferenceAsMajorityError, Unit]] { maybeStored =>
          val stored = maybeStored.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])

          stored.collectFirst { case (_, a @ AcceptedAllowSpend(tx)) if a.ref === majorityTxRef => tx }.map { majorityTx =>
            val remaining = stored.filter { case (ordinal, _) => ordinal > majorityTx.ordinal }

            remaining + (majorityTx.ordinal -> MajorityAllowSpend(AllowSpendReference.of(majorityTx), snapshotOrdinal))
          } match {
            case Some(updated) => (updated.some, ().asRight)
            case None          => (maybeStored, UnexpectedStateWhenMarkingAllowSpendRefAsMajority(source, majorityTxRef, None).asLeft)
          }
        }
    }

  def accept(hashedTx: Hashed[Transaction]): F[Unit] = {
    val parent = hashedTx.signed.value.parent
    val source = hashedTx.signed.value.source
    val reference = TransactionReference(hashedTx.signed.value.ordinal, hashedTx.hash)

    transactionsR(source)
      .modify[Either[TransactionAcceptanceError, Unit]] { maybeStored =>
        val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
        val lastAcceptedRef = getLastProcessedTransaction(stored)

        if (lastAcceptedRef.exists(_.ref === parent) || (lastAcceptedRef.isEmpty && hashedTx.ordinal == TransactionOrdinal.first)) {
          val accepted = stored.updated(hashedTx.ordinal, AcceptedTx(hashedTx))

          val (processed, stillWaitingAboveAccepted) = accepted.partition { case (ordinal, _) => ordinal <= hashedTx.ordinal }

          val updated = stillWaitingAboveAccepted.values.toList.foldLeft(processed) {
            case (acc, tx) =>
              val last = acc.last._2

              val maybeStillMatching: Option[StoredTransaction] = tx match {
                case w: WaitingTx if w.transaction.parent === last.ref    => w.some
                case p: ProcessingTx if p.transaction.parent === last.ref => p.some
                case _                                                    => none[StoredTransaction]
              }

              maybeStillMatching.fold(acc)(tx => acc + (tx.ref.ordinal -> tx))
          }

          (updated.some, ().asRight)
        } else {
          (maybeStored, ParentNotAccepted(source, lastAcceptedRef.map(_.ref), reference).asLeft)
        }
      }
      .flatMap(_.liftTo[F])
  }

  def acceptAllowSpend(hashedTx: Hashed[AllowSpend]): F[Unit] = {
    val parent = hashedTx.signed.value.parent
    val source = hashedTx.signed.value.source
    val reference = AllowSpendReference(hashedTx.signed.value.ordinal, hashedTx.hash)

    allowSpendsR(source)
      .modify[Either[AllowSpendAcceptanceError, Unit]] { maybeStored =>
        val stored = maybeStored.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])
        val lastAcceptedRef = getLastProcessedAllowSpend(stored)

        if (lastAcceptedRef.exists(_.ref === parent) || (lastAcceptedRef.isEmpty && hashedTx.ordinal == AllowSpendOrdinal.first)) {
          val accepted = stored.updated(hashedTx.ordinal, AcceptedAllowSpend(hashedTx))

          val (processed, stillWaitingAboveAccepted) = accepted.partition { case (ordinal, _) => ordinal <= hashedTx.ordinal }

          val updated = stillWaitingAboveAccepted.values.toList.foldLeft(processed) {
            case (acc, tx) =>
              val last = acc.last._2

              val maybeStillMatching: Option[StoredAllowSpend] = tx match {
                case w: WaitingAllowSpend if w.transaction.parent === last.ref    => w.some
                case p: ProcessingAllowSpend if p.transaction.parent === last.ref => p.some
                case _                                                            => none[StoredAllowSpend]
              }

              maybeStillMatching.fold(acc)(tx => acc + (tx.ref.ordinal -> tx))
          }

          (updated.some, ().asRight)
        } else {
          (maybeStored, AllowSpendParentNotAccepted(source, lastAcceptedRef.map(_.ref), reference).asLeft)
        }
      }
      .flatMap(_.liftTo[F])
  }

  def tryPut(
    transaction: Hashed[Transaction],
    lastSnapshotOrdinal: SnapshotOrdinal,
    sourceBalance: Balance
  ): F[Either[NonEmptyList[ContextualTransactionValidator.ContextualTransactionValidationError], Hash]] =
    transactionsR(transaction.source).modify { maybeStored =>
      val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
      val lastProcessedTransaction = getLastProcessedTransaction(stored).getOrElse(getInitialTx)
      val validationContext = TransactionValidatorContext(maybeStored, sourceBalance, lastProcessedTransaction.ref, lastSnapshotOrdinal)
      val validation =
        contextualTransactionValidator.validate(transaction, validationContext)

      validation match {
        case Validated.Valid(ContextualTransactionValidator.NoConflict(tx)) =>
          val updated = stored.updated(transaction.ordinal, WaitingTx(tx))
          (updated.some, transaction.hash.asRight[NonEmptyList[ContextualTransactionValidator.ContextualTransactionValidationError]])
        case Validated.Valid(ContextualTransactionValidator.CanOverride(tx)) =>
          val updated = stored.updated(transaction.ordinal, WaitingTx(tx)).filterNot { case (ordinal, _) => ordinal > tx.ordinal }
          (updated.some, transaction.hash.asRight[NonEmptyList[ContextualTransactionValidator.ContextualTransactionValidationError]])
        case Validated.Invalid(e) =>
          (maybeStored, e.toNonEmptyList.asLeft[Hash])
      }
    }

  def tryPutAllowSpend(
    allowSpend: Hashed[AllowSpend],
    lastSnapshotOrdinal: SnapshotOrdinal,
    sourceBalance: Balance
  ): F[Either[NonEmptyList[ContextualAllowSpendValidator.ContextualAllowSpendValidationError], Hash]] =
    allowSpendsR(allowSpend.source).modify { maybeStored =>
      val stored = maybeStored.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])
      val lastProcessedTransaction = getLastProcessedAllowSpend(stored).getOrElse(getInitialAllowSpend)
      val validationContext = AllowSpendValidatorContext(maybeStored, sourceBalance, lastProcessedTransaction.ref, lastSnapshotOrdinal)
      val validation =
        contextualAllowSpendValidator.validate(allowSpend, validationContext)

      validation match {
        case Validated.Valid(ContextualAllowSpendValidator.NoConflict(tx)) =>
          val updated = stored.updated(allowSpend.ordinal, WaitingAllowSpend(tx))
          (updated.some, allowSpend.hash.asRight[NonEmptyList[ContextualAllowSpendValidator.ContextualAllowSpendValidationError]])
        case Validated.Valid(ContextualAllowSpendValidator.CanOverride(tx)) =>
          val updated = stored.updated(allowSpend.ordinal, WaitingAllowSpend(tx)).filterNot { case (ordinal, _) => ordinal > tx.ordinal }
          (updated.some, allowSpend.hash.asRight[NonEmptyList[ContextualAllowSpendValidator.ContextualAllowSpendValidationError]])
        case Validated.Invalid(e) =>
          (maybeStored, e.toNonEmptyList.asLeft[Hash])
      }
    }

  def putBack(transactions: Set[Hashed[Transaction]]): F[Unit] =
    transactions
      .groupBy(_.signed.value.source)
      .toList
      .traverse {
        case (source, txs) =>
          transactionsR(source).update { maybeStored =>
            val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
            txs
              .foldLeft(stored) {
                case (acc, tx) =>
                  acc.updatedWith(tx.ordinal) {
                    case Some(ProcessingTx(existing)) if existing === tx => WaitingTx(tx).some
                    case None                                            => none
                    case existing @ _                                    => existing
                  }
              }
              .some
          }
      }
      .void

  def putBackAllowSpend(transactions: Set[Hashed[AllowSpend]]): F[Unit] =
    transactions
      .groupBy(_.signed.value.source)
      .toList
      .traverse {
        case (source, txs) =>
          allowSpendsR(source).update { maybeStored =>
            val stored = maybeStored.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])
            txs
              .foldLeft(stored) {
                case (acc, tx) =>
                  acc.updatedWith(tx.ordinal) {
                    case Some(ProcessingAllowSpend(existing)) if existing === tx => WaitingAllowSpend(tx).some
                    case None                                                    => none
                    case existing @ _                                            => existing
                  }
              }
              .some
          }
      }
      .void

  def areWaiting: F[Boolean] =
    transactionsR.toMap.map(_.values.toList.collectFirstSome(_.collectFirst { case (_, w: WaitingTx) => w }).nonEmpty)

  def areWaitingAllowSpends: F[Boolean] =
    allowSpendsR.toMap.map(_.values.toList.collectFirstSome(_.collectFirst { case (_, w: WaitingAllowSpend) => w }).nonEmpty)

  def pull(count: NonNegLong): F[Option[NonEmptyList[Hashed[Transaction]]]] =
    for {
      addresses <- transactionsR.keys
      allPulled <- addresses.traverseCollect { address =>
        transactionsR(address).modify { maybeStored =>
          maybeStored.flatMap { stored =>
            NonEmptyList.fromList(stored.values.collect { case w: WaitingTx => w.transaction }.toList).map { waitingTxs =>
              val updated = stored.map {
                case (ordinal, WaitingTx(tx)) => (ordinal, ProcessingTx(tx))
                case existing @ _             => existing
              }
              (updated.some, waitingTxs.some)
            }
          } match {
            case Some(updated) => updated
            case None          => (maybeStored, none)
          }
        }
      }.map(_.flatten)

      selected = takeFirstNHighestFeeTxs(allPulled, count)
      toReturn = allPulled.flatMap(_.toList).toSet.diff(selected.toSet)
      _ <- logger.debug(s"Pulled transactions to return: ${toReturn.size}")
      _ <- transactionLogger.debug(s"Pulled transactions to return: ${toReturn.size}, returned: ${toReturn.map(_.hash).show}")
      _ <- putBack(toReturn)
      _ <- logger.debug(s"Pulled ${selected.size} transaction(s) for consensus")
      _ <- transactionLogger.debug(s"Pulled ${selected.size} transaction(s) for consensus, pulled: ${selected.map(_.hash).show}")
    } yield NonEmptyList.fromList(selected)

  def pullAllowSpend(count: NonNegLong): F[Option[NonEmptyList[Hashed[AllowSpend]]]] =
    for {
      addresses <- allowSpendsR.keys
      allPulled <- addresses.traverseCollect { address =>
        allowSpendsR(address).modify { maybeStored =>
          maybeStored.flatMap { stored =>
            NonEmptyList.fromList(stored.values.collect { case w: WaitingAllowSpend => w.transaction }.toList).map { waitingTxs =>
              val updated = stored.map {
                case (ordinal, WaitingAllowSpend(tx)) => (ordinal, ProcessingAllowSpend(tx))
                case existing @ _                     => existing
              }
              (updated.some, waitingTxs.some)
            }
          } match {
            case Some(updated) => updated
            case None          => (maybeStored, none)
          }
        }
      }.map(_.flatten)

      selected = takeFirstNHighestFeeAllowSpends(allPulled, count)
      toReturn = allPulled.flatMap(_.toList).toSet.diff(selected.toSet)
      _ <- logger.debug(s"Pulled transactions to return: ${toReturn.size}")
      _ <- transactionLogger.debug(s"Pulled transactions to return: ${toReturn.size}, returned: ${toReturn.map(_.hash).show}")
      _ <- putBackAllowSpend(toReturn)
      _ <- logger.debug(s"Pulled ${selected.size} transaction(s) for consensus")
      _ <- transactionLogger.debug(s"Pulled ${selected.size} transaction(s) for consensus, pulled: ${selected.map(_.hash).show}")
    } yield NonEmptyList.fromList(selected)

  private def takeFirstNHighestFeeAllowSpends(
    txs: List[NonEmptyList[Hashed[AllowSpend]]],
    count: NonNegLong
  ): List[Hashed[AllowSpend]] = {
    @tailrec
    def go(
      txs: SortedSet[NonEmptyList[Hashed[AllowSpend]]],
      acc: List[Hashed[AllowSpend]]
    ): List[Hashed[AllowSpend]] =
      if (acc.size == count.value)
        acc.reverse
      else {
        txs.headOption match {
          case Some(txsNel) =>
            val updatedAcc = txsNel.head :: acc

            NonEmptyList.fromList(txsNel.tail) match {
              case Some(remainingTxs) => go(txs.tail + remainingTxs, updatedAcc)
              case None               => go(txs.tail, updatedAcc)
            }

          case None => acc.reverse
        }
      }

    val order: Order[NonEmptyList[Hashed[AllowSpend]]] =
      Order.whenEqual(Order.by(-_.head.fee.value.value), Order[NonEmptyList[Hashed[AllowSpend]]])
    val sortedTxs = SortedSet.from(txs)(order.toOrdering)

    go(sortedTxs, List.empty)
  }

  private def takeFirstNHighestFeeTxs(
    txs: List[NonEmptyList[Hashed[Transaction]]],
    count: NonNegLong
  ): List[Hashed[Transaction]] = {
    @tailrec
    def go(
      txs: SortedSet[NonEmptyList[Hashed[Transaction]]],
      acc: List[Hashed[Transaction]]
    ): List[Hashed[Transaction]] =
      if (acc.size == count.value)
        acc.reverse
      else {
        txs.headOption match {
          case Some(txsNel) =>
            val updatedAcc = txsNel.head :: acc

            NonEmptyList.fromList(txsNel.tail) match {
              case Some(remainingTxs) => go(txs.tail + remainingTxs, updatedAcc)
              case None               => go(txs.tail, updatedAcc)
            }

          case None => acc.reverse
        }
      }

    val order: Order[NonEmptyList[Hashed[Transaction]]] =
      Order.whenEqual(Order.by(-_.head.fee.value.value), Order[NonEmptyList[Hashed[Transaction]]])
    val sortedTxs = SortedSet.from(txs)(order.toOrdering)

    go(sortedTxs, List.empty)
  }

  def findWaiting(hash: Hash): F[Option[WaitingTx]] =
    transactionsR.toMap.map {
      _.values.toList.collectFirstSome {
        _.collectFirst { case (_, w: WaitingTx) if w.ref.hash === hash => w }
      }
    }

  def findWaitingAllowSpend(hash: Hash): F[Option[WaitingAllowSpend]] =
    allowSpendsR.toMap.map {
      _.values.toList.collectFirstSome {
        _.collectFirst { case (_, w: WaitingAllowSpend) if w.ref.hash === hash => w }
      }
    }
}

object TransactionStorage {
  def make[F[_]: Async](
    initialTransactionReference: TransactionReference,
    initialAllowSpendReference: AllowSpendReference,
    contextualTransactionValidator: ContextualTransactionValidator,
    contextualAllowSpendValidator: ContextualAllowSpendValidator
  ): F[TransactionStorage[F]] =
    for {
      transactions <- MapRef.ofConcurrentHashMap[F, Address, SortedMap[TransactionOrdinal, StoredTransaction]]()
      allowSpends <- MapRef.ofConcurrentHashMap[F, Address, SortedMap[AllowSpendOrdinal, StoredAllowSpend]]()
    } yield
      new TransactionStorage[F](
        transactions,
        allowSpends,
        initialTransactionReference,
        initialAllowSpendReference,
        contextualTransactionValidator,
        contextualAllowSpendValidator
      )

  def make[F[_]: Async](
    transactions: Map[Address, SortedMap[TransactionOrdinal, StoredTransaction]],
    allowSpends: Map[Address, SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
    initialTransactionReference: TransactionReference,
    initialAllowSpendReference: AllowSpendReference,
    contextualTransactionValidator: ContextualTransactionValidator,
    contextualAllowSpendValidator: ContextualAllowSpendValidator
  ): F[TransactionStorage[F]] =
    (MapRef.ofSingleImmutableMap(transactions), MapRef.ofSingleImmutableMap(allowSpends)).mapN {
      case (t, a) =>
        new TransactionStorage(
          t,
          a,
          initialTransactionReference,
          initialAllowSpendReference,
          contextualTransactionValidator,
          contextualAllowSpendValidator
        )
    }

  sealed trait AllowSpendAcceptanceError extends NoStackTrace

  private case class AllowSpendParentNotAccepted(
    source: Address,
    lastAccepted: Option[AllowSpendReference],
    attempted: AllowSpendReference
  ) extends AllowSpendAcceptanceError {
    override def getMessage: String =
      s"Allow spend not accepted in the correct order. source=${source.show} current=${lastAccepted.show} attempted=${attempted.show}"
  }

  sealed trait TransactionAcceptanceError extends NoStackTrace

  private case class ParentNotAccepted(
    source: Address,
    lastAccepted: Option[TransactionReference],
    attempted: TransactionReference
  ) extends TransactionAcceptanceError {
    override def getMessage: String =
      s"Transaction not accepted in the correct order. source=${source.show} current=${lastAccepted.show} attempted=${attempted.show}"
  }

  sealed trait MarkingTransactionReferenceAsMajorityError extends NoStackTrace
  sealed trait MarkingAllowSpendReferenceAsMajorityError extends NoStackTrace

  private case class UnexpectedStateWhenMarkingTxRefAsMajority(
    source: Address,
    toMark: TransactionReference,
    got: Option[StoredTransaction]
  ) extends MarkingTransactionReferenceAsMajorityError {
    override def getMessage: String =
      s"Unexpected state encountered when marking transaction reference=$toMark for source address=$source as majority. Got: $got"
  }

  private case class UnexpectedStateWhenMarkingAllowSpendRefAsMajority(
    source: Address,
    toMark: AllowSpendReference,
    got: Option[StoredAllowSpend]
  ) extends MarkingAllowSpendReferenceAsMajorityError {
    override def getMessage: String =
      s"Unexpected state encountered when marking allow spend reference=$toMark for source address=$source as majority. Got: $got"
  }
}

@derive(eqv)
sealed trait StoredTransaction {
  def ref: TransactionReference
}
object StoredTransaction {
  implicit val show: Show[StoredTransaction] = Show.show {
    case WaitingTx(tx)                    => s"WaitingTx(${tx.hash.show})"
    case ProcessingTx(tx)                 => s"ProcessingTx(${tx.hash.show}"
    case AcceptedTx(tx)                   => s"AcceptedTx(${tx.hash.show}"
    case MajorityTx(ref, snapshotOrdinal) => s"MajorityTx(${ref.hash.show}, ${snapshotOrdinal.show}"
  }

  implicit val order: Order[StoredTransaction] = Order[TransactionOrdinal].contramap(_.ref.ordinal)
  implicit val ordering: Ordering[StoredTransaction] = order.toOrdering

  @derive(eqv)
  sealed trait NonMajorityTx extends StoredTransaction {
    val transaction: Hashed[Transaction]
    def ref: TransactionReference = TransactionReference.of(transaction)
  }
  case class WaitingTx(transaction: Hashed[Transaction]) extends StoredTransaction with NonMajorityTx
  case class ProcessingTx(transaction: Hashed[Transaction]) extends StoredTransaction with NonMajorityTx
  case class AcceptedTx(transaction: Hashed[Transaction]) extends StoredTransaction with NonMajorityTx
  @derive(eqv)
  case class MajorityTx(ref: TransactionReference, snapshotOrdinal: SnapshotOrdinal) extends StoredTransaction
}

@derive(eqv)
sealed trait StoredAllowSpend {
  def ref: AllowSpendReference
}
object StoredAllowSpend {
  implicit val show: Show[StoredAllowSpend] = Show.show {
    case WaitingAllowSpend(tx)                    => s"WaitingTx(${tx.hash.show})"
    case ProcessingAllowSpend(tx)                 => s"ProcessingTx(${tx.hash.show}"
    case AcceptedAllowSpend(tx)                   => s"AcceptedTx(${tx.hash.show}"
    case MajorityAllowSpend(ref, snapshotOrdinal) => s"MajorityTx(${ref.hash.show}, ${snapshotOrdinal.show}"
  }

  implicit val order: Order[StoredAllowSpend] = Order[AllowSpendOrdinal].contramap(_.ref.ordinal)
  implicit val ordering: Ordering[StoredAllowSpend] = order.toOrdering

  @derive(eqv)
  sealed trait NonMajorityAllowSpend extends StoredAllowSpend {
    val transaction: Hashed[AllowSpend]
    def ref: AllowSpendReference = AllowSpendReference.of(transaction)
  }
  case class WaitingAllowSpend(transaction: Hashed[AllowSpend]) extends StoredAllowSpend with NonMajorityAllowSpend
  case class ProcessingAllowSpend(transaction: Hashed[AllowSpend]) extends StoredAllowSpend with NonMajorityAllowSpend
  case class AcceptedAllowSpend(transaction: Hashed[AllowSpend]) extends StoredAllowSpend with NonMajorityAllowSpend
  @derive(eqv)
  case class MajorityAllowSpend(ref: AllowSpendReference, snapshotOrdinal: SnapshotOrdinal) extends StoredAllowSpend
}
