package io.constellationnetwork.node.shared.domain.tokenlock

import cats._
import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.collection.MapRefUtils._
import io.constellationnetwork.node.shared.domain.tokenlock.ContextualTokenLockValidator.{
  CanOverride,
  ContextualTokenLockValidationError,
  NoConflict
}
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockStorage._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockOrdinal, TokenLockReference}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TokenLockStorage[F[_]: Async](
  tokenLocksR: MapRef[F, Address, Option[SortedMap[TokenLockOrdinal, StoredTokenLock]]],
  initialTokenLockReference: TokenLockReference,
  contextualTokenLockValidator: ContextualTokenLockValidator
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val tokenLockLogger = Slf4jLogger.getLoggerFromName[F](tokenLockLoggerName)

  def getInitialTx: MajorityTokenLock = MajorityTokenLock(initialTokenLockReference, SnapshotOrdinal.MinValue)

  def getState: F[Map[Address, SortedMap[TokenLockOrdinal, StoredTokenLock]]] = tokenLocksR.toMap

  def getLastProcessedTokenLock(source: Address): F[StoredTokenLock] =
    tokenLocksR(source).get.map {
      _.flatMap(getLastProcessedTokenLock).getOrElse(getInitialTx)
    }

  private def getLastProcessedTokenLock(stored: SortedMap[TokenLockOrdinal, StoredTokenLock]): Option[StoredTokenLock] =
    stored.collect { case tx @ (_, _: AcceptedTokenLock | _: MajorityTokenLock) => tx }.lastOption.map {
      case (_, tokenLock) => tokenLock
    }

  def initByRefs(refs: Map[Address, TokenLockReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse {
      case (address, reference) =>
        tokenLocksR(address)
          .modify[Either[Error, Unit]] {
            case curr @ Some(_) =>
              (curr, new Error("Storage should be empty before download").asLeft)
            case None =>
              val initial = SortedMap(reference.ordinal -> MajorityTokenLock(reference, snapshotOrdinal))
              (initial.some, ().asRight)
          }
          .flatMap(_.liftTo[F])
    }.void

  def replaceByRefs(refs: Map[Address, TokenLockReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (address, reference) =>
        val initial = SortedMap(reference.ordinal -> MajorityTokenLock(reference, snapshotOrdinal))
        tokenLocksR(address).set(initial.some)
    }

  def advanceMajorityRefs(refs: Map[Address, TokenLockReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (source, majorityTxRef) =>
        tokenLocksR(source).modify[Either[MarkingTokenLockReferenceAsMajorityError, Unit]] { maybeStored =>
          val stored = maybeStored.getOrElse(SortedMap.empty[TokenLockOrdinal, StoredTokenLock])

          if (stored.isEmpty && majorityTxRef === TokenLockReference.empty) {
            val updated = stored + (majorityTxRef.ordinal -> MajorityTokenLock(majorityTxRef, snapshotOrdinal))
            (updated.some, ().asRight)
          } else {
            stored.collectFirst { case (_, a @ AcceptedTokenLock(tx)) if a.ref === majorityTxRef => tx }.map { majorityTx =>
              val remaining = stored.filter { case (ordinal, _) => ordinal > majorityTx.ordinal }

              remaining + (majorityTx.ordinal -> MajorityTokenLock(TokenLockReference.of(majorityTx), snapshotOrdinal))
            } match {
              case Some(updated) => (updated.some, ().asRight)
              case None          => (maybeStored, UnexpectedStateWhenMarkingTxRefAsMajority(source, majorityTxRef, None).asLeft)
            }
          }
        }
    }

  def accept(hashedTx: Hashed[TokenLock]): F[Unit] = {
    val parent = hashedTx.signed.value.parent
    val source = hashedTx.signed.value.source
    val reference = TokenLockReference(hashedTx.signed.value.ordinal, hashedTx.hash)

    tokenLocksR(source)
      .modify[Either[TokenLockAcceptanceError, Unit]] { maybeStored =>
        val stored = maybeStored.getOrElse(SortedMap.empty[TokenLockOrdinal, StoredTokenLock])
        val lastAcceptedRef = getLastProcessedTokenLock(stored)

        if (lastAcceptedRef.exists(_.ref === parent) || (lastAcceptedRef.isEmpty && hashedTx.ordinal == TokenLockOrdinal.first)) {
          val accepted = stored.updated(hashedTx.ordinal, AcceptedTokenLock(hashedTx))

          val (processed, stillWaitingAboveAccepted) = accepted.partition { case (ordinal, _) => ordinal <= hashedTx.ordinal }

          val updated = stillWaitingAboveAccepted.values.toList.foldLeft(processed) {
            case (acc, tx) =>
              val last = acc.last._2

              val maybeStillMatching: Option[StoredTokenLock] = tx match {
                case w: WaitingTokenLock if w.transaction.parent === last.ref    => w.some
                case p: ProcessingTokenLock if p.transaction.parent === last.ref => p.some
                case _                                                           => none[StoredTokenLock]
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
    tokenLock: Hashed[TokenLock],
    lastSnapshotOrdinal: SnapshotOrdinal,
    lastEpochProgress: EpochProgress,
    sourceBalance: Balance
  ): F[Either[NonEmptyList[ContextualTokenLockValidationError], Hash]] =
    tokenLocksR(tokenLock.source).modify { maybeStored =>
      val stored = maybeStored.getOrElse(SortedMap.empty[TokenLockOrdinal, StoredTokenLock])
      val lastProcessedTokenLock = getLastProcessedTokenLock(stored).getOrElse(getInitialTx)
      val validationContext =
        TokenLockValidatorContext(maybeStored, sourceBalance, lastProcessedTokenLock.ref, lastSnapshotOrdinal, lastEpochProgress)
      val validation =
        contextualTokenLockValidator.validate(tokenLock, validationContext)

      validation match {
        case Validated.Valid(NoConflict(tx)) =>
          val updated = stored.updated(tokenLock.ordinal, WaitingTokenLock(tx))
          (updated.some, tokenLock.hash.asRight[NonEmptyList[ContextualTokenLockValidationError]])
        case Validated.Valid(CanOverride(tx)) =>
          val updated = stored.updated(tokenLock.ordinal, WaitingTokenLock(tx)).filterNot { case (ordinal, _) => ordinal > tx.ordinal }
          (updated.some, tokenLock.hash.asRight[NonEmptyList[ContextualTokenLockValidationError]])
        case Validated.Invalid(e) =>
          (maybeStored, e.toNonEmptyList.asLeft[Hash])
      }
    }

  def putBack(tokenLocks: Set[Hashed[TokenLock]]): F[Unit] =
    tokenLocks
      .groupBy(_.signed.value.source)
      .toList
      .traverse {
        case (source, txs) =>
          tokenLocksR(source).update { maybeStored =>
            val stored = maybeStored.getOrElse(SortedMap.empty[TokenLockOrdinal, StoredTokenLock])
            txs
              .foldLeft(stored) {
                case (acc, tx) =>
                  acc.updatedWith(tx.ordinal) {
                    case Some(ProcessingTokenLock(existing)) if existing === tx => WaitingTokenLock(tx).some
                    case None                                                   => none
                    case existing @ _                                           => existing
                  }
              }
              .some
          }
      }
      .void

  def areWaiting: F[Boolean] =
    tokenLocksR.toMap.map(_.values.toList.collectFirstSome(_.collectFirst { case (_, w: WaitingTokenLock) => w }).nonEmpty)

  def count: F[Int] = tokenLocksR.toMap.map(x => x.size)

  def pull(count: NonNegLong): F[Option[NonEmptyList[Hashed[TokenLock]]]] =
    for {
      addresses <- tokenLocksR.keys
      allPulled <- addresses.traverseCollect { address =>
        tokenLocksR(address).modify { maybeStored =>
          maybeStored.flatMap { stored =>
            NonEmptyList.fromList(stored.values.collect { case w: WaitingTokenLock => w.transaction }.toList).map { waitingTxs =>
              val updated = stored.map {
                case (ordinal, WaitingTokenLock(tx)) => (ordinal, ProcessingTokenLock(tx))
                case existing @ _                    => existing
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
        .map(_.flatMap(_.toList))

      selected = takeFirstNHighestFeeTxs(allPulled, count)
      toReturn = allPulled.toSet.diff(selected.toSet)
      _ <- logger.debug(s"Pulled token lock(s) to return: ${toReturn.size}")
      _ <- tokenLockLogger.debug(s"Pulled token lock(s) to return: ${toReturn.size}, returned: ${toReturn.map(_.hash).show}")
      _ <- putBack(toReturn)
      _ <- logger.debug(s"Pulled ${selected.size} token lock(s) for consensus")
      _ <- tokenLockLogger.debug(s"Pulled ${selected.size} token lock(s) for consensus, pulled: ${selected.map(_.hash).show}")
    } yield NonEmptyList.fromList(selected)

  private def takeFirstNHighestFeeTxs(
    txs: List[Hashed[TokenLock]],
    count: NonNegLong
  ): List[Hashed[TokenLock]] = {
    val order: Order[Hashed[TokenLock]] =
      Order.whenEqual(Order.by(-_.fee.value.value), Order[Hashed[TokenLock]])

    val sortedTxs = SortedSet.from(txs)(order.toOrdering)

    @tailrec
    def go(
      txs: SortedSet[Hashed[TokenLock]],
      acc: List[Hashed[TokenLock]]
    ): List[Hashed[TokenLock]] =
      if (acc.size >= count.value) acc.reverse
      else
        txs.headOption match {
          case Some(tx) => go(txs.tail, tx :: acc)
          case None     => acc.reverse
        }

    go(sortedTxs, Nil)
  }

  def findWaiting(hash: Hash): F[Option[WaitingTokenLock]] =
    tokenLocksR.toMap.map {
      _.values.toList.collectFirstSome {
        _.collectFirst { case (_, w: WaitingTokenLock) if w.ref.hash === hash => w }
      }
    }
}

object TokenLockStorage {
  def make[F[_]: Async](
    initialTokenLockReference: TokenLockReference,
    contextualTokenLockValidator: ContextualTokenLockValidator
  ): F[TokenLockStorage[F]] =
    for {
      tokenLocks <- MapRef.ofConcurrentHashMap[F, Address, SortedMap[TokenLockOrdinal, StoredTokenLock]]()
    } yield
      new TokenLockStorage[F](
        tokenLocks,
        initialTokenLockReference,
        contextualTokenLockValidator
      )

  def make[F[_]: Async](
    tokenLocks: Map[Address, SortedMap[TokenLockOrdinal, StoredTokenLock]],
    initialTokenLockReference: TokenLockReference,
    contextualTokenLockValidator: ContextualTokenLockValidator
  ): F[TokenLockStorage[F]] =
    MapRef.ofSingleImmutableMap(tokenLocks).map(new TokenLockStorage(_, initialTokenLockReference, contextualTokenLockValidator))

  sealed trait TokenLockAcceptanceError extends NoStackTrace

  private case class ParentNotAccepted(
    source: Address,
    lastAccepted: Option[TokenLockReference],
    attempted: TokenLockReference
  ) extends TokenLockAcceptanceError {
    override def getMessage: String =
      s"TokenLock not accepted in the correct order. source=${source.show} current=${lastAccepted.show} attempted=${attempted.show}"
  }

  sealed trait MarkingTokenLockReferenceAsMajorityError extends NoStackTrace

  private case class UnexpectedStateWhenMarkingTxRefAsMajority(
    source: Address,
    toMark: TokenLockReference,
    got: Option[StoredTokenLock]
  ) extends MarkingTokenLockReferenceAsMajorityError {
    override def getMessage: String =
      s"Unexpected state encountered when marking token lock reference=$toMark for source address=$source as majority. Got: $got"
  }
}

@derive(eqv)
sealed trait StoredTokenLock {
  def ref: TokenLockReference
}
object StoredTokenLock {
  implicit val show: Show[StoredTokenLock] = Show.show {
    case WaitingTokenLock(tx)                    => s"WaitingTokenLock(${tx.hash.show})"
    case ProcessingTokenLock(tx)                 => s"ProcessingTokenLock(${tx.hash.show}"
    case AcceptedTokenLock(tx)                   => s"AcceptedTokenLock(${tx.hash.show}"
    case MajorityTokenLock(ref, snapshotOrdinal) => s"MajorityTokenLock(${ref.hash.show}, ${snapshotOrdinal.show}"
  }

  implicit val order: Order[StoredTokenLock] = Order[TokenLockOrdinal].contramap(_.ref.ordinal)
  implicit val ordering: Ordering[StoredTokenLock] = order.toOrdering
}
@derive(eqv)
sealed trait NonMajorityTokenLock extends StoredTokenLock {
  val transaction: Hashed[TokenLock]
  def ref: TokenLockReference = TokenLockReference.of(transaction)
}
case class WaitingTokenLock(transaction: Hashed[TokenLock]) extends StoredTokenLock with NonMajorityTokenLock
case class ProcessingTokenLock(transaction: Hashed[TokenLock]) extends StoredTokenLock with NonMajorityTokenLock
case class AcceptedTokenLock(transaction: Hashed[TokenLock]) extends StoredTokenLock with NonMajorityTokenLock

@derive(eqv)
case class MajorityTokenLock(ref: TokenLockReference, snapshotOrdinal: SnapshotOrdinal) extends StoredTokenLock
