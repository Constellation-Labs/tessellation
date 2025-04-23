package io.constellationnetwork.node.shared.domain.swap

import cats._
import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.collection.MapRefUtils._
import io.constellationnetwork.node.shared.domain.swap.ContextualAllowSpendValidator.{
  CanOverride,
  ContextualAllowSpendValidationError,
  NoConflict
}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendOrdinal, AllowSpendReference}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

import AllowSpendStorage.MarkingAllowSpendReferenceAsMajorityError
import AllowSpendStorage.UnexpectedStateWhenMarkingTxRefAsMajority
import AllowSpendStorage.AllowSpendAcceptanceError
import AllowSpendStorage.ParentNotAccepted

class AllowSpendStorage[F[_]: Async](
  allowSpendsR: MapRef[F, Address, Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]]],
  initialAllowSpendReference: AllowSpendReference,
  contextualAllowSpendValidator: ContextualAllowSpendValidator
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val allowSpendLogger = Slf4jLogger.getLoggerFromName[F](allowSpendLoggerName)

  def getInitialTx: MajorityAllowSpend = MajorityAllowSpend(initialAllowSpendReference, SnapshotOrdinal.MinValue)

  def getState: F[Map[Address, SortedMap[AllowSpendOrdinal, StoredAllowSpend]]] = allowSpendsR.toMap

  def getLastProcessedAllowSpend(source: Address): F[StoredAllowSpend] =
    allowSpendsR(source).get.map {
      _.flatMap(getLastProcessedAllowSpend).getOrElse(getInitialTx)
    }

  private def getLastProcessedAllowSpend(stored: SortedMap[AllowSpendOrdinal, StoredAllowSpend]): Option[StoredAllowSpend] =
    stored.collect { case tx @ (_, _: AcceptedAllowSpend | _: MajorityAllowSpend) => tx }.lastOption.map {
      case (_, allowSpend) => allowSpend
    }

  def initByRefs(refs: Map[Address, AllowSpendReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
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

  def replaceByRefs(refs: Map[Address, AllowSpendReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (address, reference) =>
        val initial = SortedMap(reference.ordinal -> MajorityAllowSpend(reference, snapshotOrdinal))
        allowSpendsR(address).set(initial.some)
    }

  def advanceMajorityRefs(refs: Map[Address, AllowSpendReference], snapshotOrdinal: SnapshotOrdinal): F[Unit] =
    refs.toList.traverse_ {
      case (source, majorityTxRef) =>
        allowSpendsR(source).modify[Either[MarkingAllowSpendReferenceAsMajorityError, Unit]] { maybeStored =>
          val stored = maybeStored.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])

          if (stored.isEmpty && majorityTxRef === AllowSpendReference.empty) {
            val updated = stored + (majorityTxRef.ordinal -> MajorityAllowSpend(majorityTxRef, snapshotOrdinal))
            (updated.some, ().asRight)
          } else {
            stored.collectFirst { case (_, a @ AcceptedAllowSpend(tx)) if a.ref === majorityTxRef => tx }.map { majorityTx =>
              val remaining = stored.filter { case (ordinal, _) => ordinal > majorityTx.ordinal }

              remaining + (majorityTx.ordinal -> MajorityAllowSpend(AllowSpendReference.of(majorityTx), snapshotOrdinal))
            } match {
              case Some(updated) => (updated.some, ().asRight)
              case None          => (maybeStored, UnexpectedStateWhenMarkingTxRefAsMajority(source, majorityTxRef, None).asLeft)
            }
          }
        }
    }

  def accept(hashedTx: Hashed[AllowSpend]): F[Unit] = {
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
          (maybeStored, ParentNotAccepted(source, lastAcceptedRef.map(_.ref), reference).asLeft)
        }
      }
      .flatMap(_.liftTo[F])
  }

  def tryPut(
    allowSpend: Hashed[AllowSpend],
    lastSnapshotOrdinal: SnapshotOrdinal,
    lastEpochProgress: EpochProgress,
    sourceBalance: Balance
  ): F[Either[NonEmptyList[ContextualAllowSpendValidationError], Hash]] =
    allowSpendsR(allowSpend.source).modify { maybeStored =>
      val stored = maybeStored.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])
      val lastProcessedAllowSpend = getLastProcessedAllowSpend(stored).getOrElse(getInitialTx)
      val validationContext =
        AllowSpendValidatorContext(maybeStored, sourceBalance, lastProcessedAllowSpend.ref, lastSnapshotOrdinal, lastEpochProgress)
      val validation =
        contextualAllowSpendValidator.validate(allowSpend, validationContext)

      validation match {
        case Validated.Valid(NoConflict(tx)) =>
          val updated = stored.updated(allowSpend.ordinal, WaitingAllowSpend(tx))
          (updated.some, allowSpend.hash.asRight[NonEmptyList[ContextualAllowSpendValidationError]])
        case Validated.Valid(CanOverride(tx)) =>
          val updated = stored.updated(allowSpend.ordinal, WaitingAllowSpend(tx)).filterNot { case (ordinal, _) => ordinal > tx.ordinal }
          (updated.some, allowSpend.hash.asRight[NonEmptyList[ContextualAllowSpendValidationError]])
        case Validated.Invalid(e) =>
          (maybeStored, e.toNonEmptyList.asLeft[Hash])
      }
    }

  def putBack(allowSpends: Set[Hashed[AllowSpend]]): F[Unit] =
    allowSpends
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
    allowSpendsR.toMap.map(_.values.toList.collectFirstSome(_.collectFirst { case (_, w: WaitingAllowSpend) => w }).nonEmpty)

  def pull(count: NonNegLong): F[Option[NonEmptyList[Hashed[AllowSpend]]]] =
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
      }
        .map(_.flatten)
        .map(_.flatMap(_.toList))

      selected = takeFirstNHighestFeeTxs(allPulled, count)
      toReturn = allPulled.toSet.diff(selected.toSet)
      _ <- logger.debug(s"Pulled allow spends to return: ${toReturn.size}")
      _ <- allowSpendLogger.debug(s"Pulled allow spends to return: ${toReturn.size}, returned: ${toReturn.map(_.hash).show}")
      _ <- putBack(toReturn)
      _ <- logger.debug(s"Pulled ${selected.size} allow spend(s) for consensus")
      _ <- allowSpendLogger.debug(s"Pulled ${selected.size} allow spend(s) for consensus, pulled: ${selected.map(_.hash).show}")
    } yield NonEmptyList.fromList(selected)

  private def takeFirstNHighestFeeTxs(
    txs: List[Hashed[AllowSpend]],
    count: NonNegLong
  ): List[Hashed[AllowSpend]] = {
    val order: Order[Hashed[AllowSpend]] =
      Order.whenEqual(Order.by(-_.fee.value.value), Order[Hashed[AllowSpend]])

    val sortedTxs = SortedSet.from(txs)(order.toOrdering)

    @tailrec
    def go(
      txs: SortedSet[Hashed[AllowSpend]],
      acc: List[Hashed[AllowSpend]]
    ): List[Hashed[AllowSpend]] =
      if (acc.size >= count.value) acc.reverse
      else
        txs.headOption match {
          case Some(tx) => go(txs.tail, tx :: acc)
          case None     => acc.reverse
        }

    go(sortedTxs, Nil)
  }

  def findWaiting(hash: Hash): F[Option[WaitingAllowSpend]] =
    allowSpendsR.toMap.map {
      _.values.toList.collectFirstSome {
        _.collectFirst { case (_, w: WaitingAllowSpend) if w.ref.hash === hash => w }
      }
    }
}

object AllowSpendStorage {
  def make[F[_]: Async](
    initialAllowSpendReference: AllowSpendReference,
    contextualAllowSpendValidator: ContextualAllowSpendValidator
  ): F[AllowSpendStorage[F]] =
    for {
      allowSpends <- MapRef.ofConcurrentHashMap[F, Address, SortedMap[AllowSpendOrdinal, StoredAllowSpend]]()
    } yield
      new AllowSpendStorage[F](
        allowSpends,
        initialAllowSpendReference,
        contextualAllowSpendValidator
      )

  def make[F[_]: Async](
    allowSpends: Map[Address, SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
    initialAllowSpendReference: AllowSpendReference,
    contextualAllowSpendValidator: ContextualAllowSpendValidator
  ): F[AllowSpendStorage[F]] =
    MapRef.ofSingleImmutableMap(allowSpends).map(new AllowSpendStorage(_, initialAllowSpendReference, contextualAllowSpendValidator))

  sealed trait AllowSpendAcceptanceError extends NoStackTrace

  private case class ParentNotAccepted(
    source: Address,
    lastAccepted: Option[AllowSpendReference],
    attempted: AllowSpendReference
  ) extends AllowSpendAcceptanceError {
    override def getMessage: String =
      s"AllowSpend not accepted in the correct order. source=${source.show} current=${lastAccepted.show} attempted=${attempted.show}"
  }

  sealed trait MarkingAllowSpendReferenceAsMajorityError extends NoStackTrace

  private case class UnexpectedStateWhenMarkingTxRefAsMajority(
    source: Address,
    toMark: AllowSpendReference,
    got: Option[StoredAllowSpend]
  ) extends MarkingAllowSpendReferenceAsMajorityError {
    override def getMessage: String =
      s"Unexpected state encountered when marking allow spend reference=$toMark for source address=$source as majority. Got: $got"
  }
}

@derive(eqv)
sealed trait StoredAllowSpend {
  def ref: AllowSpendReference
}
object StoredAllowSpend {
  implicit val show: Show[StoredAllowSpend] = Show.show {
    case WaitingAllowSpend(tx)                    => s"WaitingAllowSpend(${tx.hash.show})"
    case ProcessingAllowSpend(tx)                 => s"ProcessingAllowSpend(${tx.hash.show}"
    case AcceptedAllowSpend(tx)                   => s"AcceptedAllowSpend(${tx.hash.show}"
    case MajorityAllowSpend(ref, snapshotOrdinal) => s"MajorityAllowSpend(${ref.hash.show}, ${snapshotOrdinal.show}"
  }

  implicit val order: Order[StoredAllowSpend] = Order[AllowSpendOrdinal].contramap(_.ref.ordinal)
  implicit val ordering: Ordering[StoredAllowSpend] = order.toOrdering
}
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
