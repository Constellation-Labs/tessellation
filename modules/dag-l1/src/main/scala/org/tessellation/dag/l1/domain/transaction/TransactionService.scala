package org.tessellation.dag.l1.domain.transaction

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Sync
import cats.syntax.alternative._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.transaction.TransactionService.{ParentNotAccepted, TransactionAcceptanceError}
import org.tessellation.dag.transaction.filter.Consecutive
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TransactionService[F[_]: Sync](
  lastAccepted: MapRef[F, Address, Option[TransactionReference]],
  waitingTransactions: MapRef[F, Address, Option[NonEmptySet[Signed[Transaction]]]]
) {

  implicit val logger = Slf4jLogger.getLogger[F]

  def isParentAccepted(transaction: Transaction): F[Boolean] =
    (transaction.parent != TransactionReference.empty)
      .guard[Option]
      .fold(true.pure[F]) { _ =>
        lastAccepted(transaction.source).get.map(_.contains(transaction.parent))
      }

  def getLastAcceptedReference(source: Address): F[TransactionReference] =
    lastAccepted(source).get.map(_.getOrElse(TransactionReference.empty))

  def accept(hashedTx: Hashed[Transaction]): F[Unit] = {
    val parent = hashedTx.signed.value.parent
    val source = hashedTx.signed.value.source
    val reference = TransactionReference(hashedTx.hash, hashedTx.signed.value.ordinal)

    lastAccepted(source)
      .modify[Either[TransactionAcceptanceError, Unit]] { lastAccepted =>
        if (lastAccepted.contains(parent) || (lastAccepted.isEmpty && reference.ordinal == TransactionOrdinal.first))
          (reference.some, ().asRight)
        else
          (lastAccepted, ParentNotAccepted(source, lastAccepted, reference).asLeft)
      }
      .flatMap(_.liftTo[F])
  }

  def put(transactions: Set[Signed[Transaction]]): F[Unit] =
    transactions
      .groupBy(_.value.source)
      .toList
      .traverse {
        case (source, txs) =>
          waitingTransactions(source).modify { current =>
            val updated = current match {
              case Some(waiting) =>
                NonEmptySet.fromSet(SortedSet.from(waiting.toNonEmptyList.toList ++ txs.toList))
              case None =>
                NonEmptySet.fromSet(SortedSet.from(txs.toList))
            }

            (updated, ())
          }
      }
      .void

  def pull(): F[Option[NonEmptyList[Signed[Transaction]]]] =
    for {
      lastAccepted <- lastAccepted.toMap
      addresses <- waitingTransactions.keys
      pulled <- addresses.traverse { address =>
        waitingTransactions(address).modify {
          case Some(waiting) =>
            val lastTx = lastAccepted.getOrElse(address, TransactionReference.empty)
            val pulledForAddress = Consecutive.take(lastTx, waiting.toNonEmptyList.toList)
            val updatedStillWaiting =
              NonEmptySet.fromSet(SortedSet.from(pulledForAddress)) match {
                case Some(pulledTxs) => NonEmptySet.fromSet(waiting.diff(pulledTxs))
                case None            => waiting.some
              }
            (updatedStillWaiting, pulledForAddress)
          case None =>
            (None, Seq.empty)
        }.handleErrorWith {
          logger
            .warn(_)(s"Error while pulling transactions for address=${address.show} from waiting pool.")
            .map(_ => Seq.empty)
        }
      }.map(_.flatten)
    } yield NonEmptyList.fromList(pulled)

}

object TransactionService {

  def make[F[_]: Sync]: F[TransactionService[F]] =
    for {
      lastAccepted <- MapRef.ofConcurrentHashMap[F, Address, TransactionReference]()
      waitingTransactions <- MapRef.ofConcurrentHashMap[F, Address, NonEmptySet[Signed[Transaction]]]()
      transactionStorage = new TransactionService[F](lastAccepted, waitingTransactions)
    } yield transactionStorage

  sealed trait TransactionAcceptanceError extends NoStackTrace
  case class ParentNotAccepted(
    source: Address,
    lastAccepted: Option[TransactionReference],
    attempted: TransactionReference
  ) extends TransactionAcceptanceError {
    override def getMessage: String =
      s"Transaction not accepted in the correct order. source=${source.show} current=${lastAccepted.show} attempted=${attempted.show}"
  }
}
