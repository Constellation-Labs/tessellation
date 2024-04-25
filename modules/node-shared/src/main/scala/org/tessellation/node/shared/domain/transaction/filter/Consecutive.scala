package org.tessellation.node.shared.domain.transaction.filter

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.contravariant._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import cats.{Applicative, Id, Order}

import scala.annotation.tailrec

import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher}

object Consecutive {
  def signedTxOrder: Order[Signed[Transaction]] =
    Order.whenEqual(Order.by(-_.fee.value.value), Order[Signed[Transaction]])

  def hashedTxOrder: Order[Hashed[Transaction]] = signedTxOrder.contramap(_.signed)

  def take[F[_]: Async](txs: List[Signed[Transaction]], txHasher: Hasher[F]): F[List[Signed[Transaction]]] = {
    val headTx =
      txs.sorted(Order.whenEqual(Order.by[Signed[Transaction], Long](_.ordinal.value.value), signedTxOrder).toOrdering).headOption

    headTx match {
      case None => Applicative[F].pure(List.empty)
      case Some(headTx) =>
        implicit val hasher = txHasher
        TransactionReference
          .of(headTx)
          .flatMap { headTxReference =>
            takeGeneric[F, TransactionReference, Signed[Transaction]](
              signedTx => TransactionReference.of(signedTx),
              _.parent,
              txs.diff(Seq(headTx)),
              headTxReference
            )(Applicative[F], Order[TransactionReference], signedTxOrder).map(_ :+ headTx)
          }
    }
  }

  def take(
    txs: List[Hashed[Transaction]],
    lastAcceptedTxRef: TransactionReference
  ): List[Hashed[Transaction]] =
    takeGeneric[Id, TransactionReference, Hashed[Transaction]](
      hashedTx => Id(TransactionReference.of(hashedTx)),
      _.parent,
      txs,
      lastAcceptedTxRef
    )(Applicative[Id], Order[TransactionReference], hashedTxOrder)

  private def takeGeneric[F[_]: Applicative, Ref: Order, Item: Order](
    refOf: Item => F[Ref],
    prevRefOf: Item => Ref,
    items: List[Item],
    initRef: Ref
  ): F[List[Item]] =
    items.traverse { item =>
      refOf(item).map(_ -> item)
    }.map { refItemPairs =>
      val grouped = refItemPairs.groupByNel { case (_, item) => prevRefOf(item) }.view.mapValues(_.sortBy(_._2))

      @tailrec
      def loop(acc: List[Item], prevRef: Ref): List[Item] =
        grouped.get(prevRef) match {
          case Some(NonEmptyList((ref, item), _)) =>
            loop(item :: acc, ref)
          case None =>
            acc
        }

      loop(List.empty, initRef).reverse
    }

}
