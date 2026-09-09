package io.constellationnetwork.dag.l1.domain.transaction

import cats._
import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l1.domain.transaction.ContextualTransactionValidator.{
  CanOverride,
  ContextualTransactionValidationError,
  NoConflict
}
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage._
import io.constellationnetwork.ext.collection.MapRefUtils._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TransactionStorage[F[_]: Async](
  transactionsR: MapRef[F, Address, Option[SortedMap[TransactionOrdinal, StoredTransaction]]],
  initialTransactionReference: TransactionReference,
  contextualTransactionValidator: ContextualTransactionValidator
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val transactionLogger = Slf4jLogger.getLoggerFromName[F](transactionLoggerName)

  def getInitialTx: MajorityTx = MajorityTx(initialTransactionReference, SnapshotOrdinal.MinValue)

  def getState: F[Map[Address, SortedMap[TransactionOrdinal, StoredTransaction]]] = transactionsR.toMap

  def getLastProcessedTransaction(source: Address): F[StoredTransaction] =
    transactionsR(source).get.map {
      _.flatMap(getLastProcessedTransaction).getOrElse(getInitialTx)
    }

  private def getLastProcessedTransaction(stored: SortedMap[TransactionOrdinal, StoredTransaction]): Option[StoredTransaction] =
    stored.collect { case tx @ (_, _: AcceptedTx | _: MajorityTx) => tx }.lastOption.map { case (_, transaction) => transaction }

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

  def replaceByRefs(refs: Map[Address, TransactionReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (address, reference) =>
        transactionsR(address).update { maybeStored =>
          val majority: StoredTransaction = MajorityTx(reference, snapshotOrdinal)
          // Reset the address to the majority ref, but PRESERVE in-flight Waiting/Processing txs strictly ABOVE it.
          // A blind `.set` dropped a ProcessingTx that a concurrent consensus round was about to return via putBack
          // (putBack only restores ProcessingTx), silently losing a valid in-flight client tx. Accepted txs above the
          // majority ARE discarded -- a redownload makes the majority ref the source of truth.
          val preserved = maybeStored.toList.flatMap(_.collect {
            case entry @ (ordinal, _: WaitingTx) if ordinal > reference.ordinal    => entry
            case entry @ (ordinal, _: ProcessingTx) if ordinal > reference.ordinal => entry
          })
          (SortedMap(reference.ordinal -> majority) ++ preserved).some
        }
    }

  def advanceMajorityRefs(refs: Map[Address, TransactionReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (source, majorityTxRef) =>
        transactionsR(source)
          .modify[Either[MarkingTransactionReferenceAsMajorityError, Unit]] { maybeStored =>
            val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])

            if (stored.isEmpty && majorityTxRef === TransactionReference.empty) {
              val updated = stored + (majorityTxRef.ordinal -> MajorityTx(majorityTxRef, snapshotOrdinal))
              (updated.some, ().asRight)
            } else {
              stored.collectFirst { case (_, a @ AcceptedTx(tx)) if a.ref === majorityTxRef => tx }.map { majorityTx =>
                val remaining = stored.filter { case (ordinal, _) => ordinal > majorityTx.ordinal }

                remaining + (majorityTx.ordinal -> MajorityTx(TransactionReference.of(majorityTx), snapshotOrdinal))
              } match {
                case Some(updated) => (updated.some, ().asRight)
                // Idempotent re-mark: the ref is already stored as MajorityTx (advanced by a prior alignment).
                // getLastProcessedTransaction already reports the correct ref, so this is a no-op, not a divergence.
                case None if stored.exists { case (_, m: MajorityTx) => m.ref === majorityTxRef; case _ => false } =>
                  (maybeStored, ().asRight)
                case None =>
                  (
                    maybeStored,
                    UnexpectedStateWhenMarkingTxRefAsMajority(source, majorityTxRef, getLastProcessedTransaction(stored)).asLeft
                  )
              }
            }
          }
          .flatMap {
            case Right(_) => Async[F].unit
            // Previously the Either was discarded, silently leaving the local ref stale while the snapshot advanced.
            // Surface it: this is a genuine local-vs-majority divergence that must escalate to a redownload.
            case Left(e) =>
              logger.warn(s"advanceMajorityRefs could not mark majority ref for source=${source.show}: ${e.getMessage}") >>
                e.raiseError[F, Unit]
          }
    }

  /** Pure precondition check (no mutation) for `advanceMajorityRefs`: returns the addresses for which advancing to the given majority ref
    * would FAIL (no matching local AcceptedTx and not already a MajorityTx at that ref, and not the empty special case). Run BEFORE any
    * storage mutation in processAlignment so a divergence aborts the whole apply cleanly, instead of `advanceMajorityRefs` raising AFTER
    * block storage was already mutated (cross-store partial commit). The predicate mirrors the accept conditions of `advanceMajorityRefs`
    * exactly.
    */
  def majorityRefsViolations(refs: Map[Address, TransactionReference]): F[List[String]] =
    refs.toList.traverse {
      case (source, majorityTxRef) =>
        transactionsR(source).get.map { maybeStored =>
          val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
          val ok =
            (stored.isEmpty && majorityTxRef === TransactionReference.empty) ||
              stored.exists { case (_, a: AcceptedTx) => a.ref === majorityTxRef; case _ => false } ||
              stored.exists { case (_, m: MajorityTx) => m.ref === majorityTxRef; case _ => false }
          if (ok) none[String] else s"tx:${source.show}->${majorityTxRef.show}".some
        }
    }.map(_.flatten)

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

  def tryPut(
    transaction: Hashed[Transaction],
    lastSnapshotOrdinal: SnapshotOrdinal,
    sourceBalance: Balance
  ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
    transactionsR(transaction.source).modify { maybeStored =>
      val stored = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
      val lastProcessedTransaction = getLastProcessedTransaction(stored).getOrElse(getInitialTx)
      val validationContext = TransactionValidatorContext(maybeStored, sourceBalance, lastProcessedTransaction.ref, lastSnapshotOrdinal)
      val validation =
        contextualTransactionValidator.validate(transaction, validationContext)

      validation match {
        case Validated.Valid(NoConflict(tx)) =>
          val updated = stored.updated(transaction.ordinal, WaitingTx(tx))
          (updated.some, transaction.hash.asRight[NonEmptyList[ContextualTransactionValidationError]])
        case Validated.Valid(CanOverride(tx)) =>
          val updated = stored.updated(transaction.ordinal, WaitingTx(tx)).filterNot { case (ordinal, _) => ordinal > tx.ordinal }
          (updated.some, transaction.hash.asRight[NonEmptyList[ContextualTransactionValidationError]])
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

  def areWaiting: F[Boolean] =
    transactionsR.toMap.map(_.values.toList.collectFirstSome(_.collectFirst { case (_, w: WaitingTx) => w }).nonEmpty)

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
      }
        .map(_.flatten)
        .map(_.map(_.toList))

      selected = takeFirstNHighestFeeParentClosedTxs(allPulled, count)
      toReturn = allPulled.flatten.toSet.diff(selected.toSet)
      _ <- logger.debug(s"Pulled transactions to return: ${toReturn.size}")
      _ <- transactionLogger.debug(s"Pulled transactions to return: ${toReturn.size}, returned: ${toReturn.map(_.hash).show}")
      _ <- putBack(toReturn)
      _ <- logger.debug(s"Pulled ${selected.size} transaction(s) for consensus")
      _ <- transactionLogger.debug(s"Pulled ${selected.size} transaction(s) for consensus, pulled: ${selected.map(_.hash).show}")
    } yield NonEmptyList.fromList(selected)

  private def takeFirstNHighestFeeParentClosedTxs(
    txsBySource: List[List[Hashed[Transaction]]],
    count: NonNegLong
  ): List[Hashed[Transaction]] = {
    val order: Order[Hashed[Transaction]] =
      Order.whenEqual(Order.by(-_.fee.value.value), Order[Hashed[Transaction]])

    @tailrec
    def go(
      remaining: List[List[Hashed[Transaction]]],
      acc: List[Hashed[Transaction]]
    ): List[Hashed[Transaction]] = {
      val nonEmpty = remaining.filter(_.nonEmpty)

      if (acc.size >= count.value || nonEmpty.isEmpty) acc.reverse
      else {
        val (selectedChain, selectedIndex) = nonEmpty.zipWithIndex.sortBy(_._1.head)(order.toOrdering).head
        val selected = selectedChain.head
        val updated = nonEmpty.updated(selectedIndex, selectedChain.tail)

        go(updated, selected :: acc)
      }
    }

    go(txsBySource, Nil)
  }

  def findWaiting(hash: Hash): F[Option[WaitingTx]] =
    transactionsR.toMap.map {
      _.values.toList.collectFirstSome {
        _.collectFirst { case (_, w: WaitingTx) if w.ref.hash === hash => w }
      }
    }
}

object TransactionStorage {
  def make[F[_]: Async](
    initialTransactionReference: TransactionReference,
    contextualTransactionValidator: ContextualTransactionValidator
  ): F[TransactionStorage[F]] =
    for {
      transactions <- MapRef.ofConcurrentHashMap[F, Address, SortedMap[TransactionOrdinal, StoredTransaction]]()
    } yield
      new TransactionStorage[F](
        transactions,
        initialTransactionReference,
        contextualTransactionValidator
      )

  def make[F[_]: Async](
    transactions: Map[Address, SortedMap[TransactionOrdinal, StoredTransaction]],
    initialTransactionReference: TransactionReference,
    contextualTransactionValidator: ContextualTransactionValidator
  ): F[TransactionStorage[F]] =
    MapRef.ofSingleImmutableMap(transactions).map(new TransactionStorage(_, initialTransactionReference, contextualTransactionValidator))

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

  private case class UnexpectedStateWhenMarkingTxRefAsMajority(
    source: Address,
    toMark: TransactionReference,
    got: Option[StoredTransaction]
  ) extends MarkingTransactionReferenceAsMajorityError {
    override def getMessage: String =
      s"Unexpected state encountered when marking transaction reference=$toMark for source address=$source as majority. Got: $got"
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
}
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
