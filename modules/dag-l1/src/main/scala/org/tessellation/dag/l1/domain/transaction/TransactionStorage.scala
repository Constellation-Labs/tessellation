package org.tessellation.dag.l1.domain.transaction

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.alternative._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.set._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.transaction.TransactionStorage.{ParentNotAccepted, TransactionAcceptanceError}
import org.tessellation.dag.transaction.filter.Consecutive
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TransactionStorage[F[_]: Async: KryoSerializer](
  lastAccepted: MapRef[F, Address, Option[TransactionReference]],
  waitingTransactions: MapRef[F, Address, Option[NonEmptySet[Signed[Transaction]]]]
) {

  private val logger = Slf4jLogger.getLogger[F]

  def isParentAccepted(transaction: Transaction): F[Boolean] =
    (transaction.parent != TransactionReference.empty)
      .guard[Option]
      .fold(true.pure[F]) { _ =>
        lastAccepted(transaction.source).get.map(_.contains(transaction.parent))
      }

  def getLastAcceptedReference(source: Address): F[TransactionReference] =
    lastAccepted(source).get.map(_.getOrElse(TransactionReference.empty))

  def setLastAccepted(lastTxRefs: Map[Address, TransactionReference]): F[Unit] =
    lastAccepted.clear >>
      lastTxRefs.toList.traverse {
        case (address, reference) => lastAccepted(address).set(reference.some)
      }.void

  def accept(hashedTx: Hashed[Transaction]): F[Unit] = {
    val parent = hashedTx.signed.value.parent
    val source = hashedTx.signed.value.source
    val reference = TransactionReference(hashedTx.signed.value.ordinal, hashedTx.hash)

    lastAccepted(source)
      .modify[Either[TransactionAcceptanceError, Unit]] { lastAccepted =>
        if (lastAccepted.contains(parent) || (lastAccepted.isEmpty && reference.ordinal == TransactionOrdinal.first))
          (reference.some, ().asRight)
        else
          (lastAccepted, ParentNotAccepted(source, lastAccepted, reference).asLeft)
      }
      .flatMap(_.liftTo[F])
  }

  def put(transaction: Signed[Transaction]): F[Unit] = put(Set(transaction))

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

  def countAllowedForConsensus: F[Int] =
    for {
      lastAccepted <- lastAccepted.toMap
      addresses <- waitingTransactions.keys
      txs <- addresses.traverse { address =>
        waitingTransactions(address).get.flatMap {
          case Some(waiting) =>
            val lastTx = lastAccepted.getOrElse(address, TransactionReference.empty)
            Consecutive.take[F](waiting.toNonEmptyList.toList, lastTx)
          case None =>
            List.empty[Signed[Transaction]].pure[F]
        }
      }.map(_.flatten)
    } yield txs.size

  def pull(): F[Option[NonEmptyList[Signed[Transaction]]]] =
    for {
      lastAccepted <- lastAccepted.toMap
      addresses <- waitingTransactions.keys
      allPulled <- addresses.traverse { address =>
        val pulledM = for {
          (maybeWaiting, setter) <- waitingTransactions(address).access

          maybePulled <- maybeWaiting.traverse { waiting =>
            val lastTx = lastAccepted.getOrElse(address, TransactionReference.empty)
            Consecutive
              .take[F](waiting.toList, lastTx)
              .map { pulled =>
                (SortedSet.from(waiting.toList.diff(pulled)).toNes, pulled)
              }
              .flatMap {
                case (maybeStillWaiting, pulled) =>
                  setter(maybeStillWaiting)
                    .ifM(
                      logger.debug(s"Pulled ${pulled.size} transaction(s) for consensus").as(pulled),
                      logger.debug("Concurrent update occurred while trying to pull transactions") >>
                        List.empty[Signed[Transaction]].pure[F]
                    )
              }
          }
          pulled = maybePulled.getOrElse(List.empty)
        } yield pulled

        pulledM.handleErrorWith {
          logger.warn(_)(s"Error while pulling transactions for address=${address.show} from waiting pool.") >>
            List.empty.pure[F]
        }
      }.map(_.flatten)
    } yield NonEmptyList.fromList(allPulled)

}

object TransactionStorage {

  def make[F[_]: Async: KryoSerializer]: F[TransactionStorage[F]] =
    for {
      lastAccepted <- MapRef.ofConcurrentHashMap[F, Address, TransactionReference]()
      waitingTransactions <- MapRef.ofConcurrentHashMap[F, Address, NonEmptySet[Signed[Transaction]]]()
      transactionStorage = new TransactionStorage[F](lastAccepted, waitingTransactions)
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
