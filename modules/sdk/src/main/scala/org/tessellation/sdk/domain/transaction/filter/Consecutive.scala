package org.tessellation.sdk.domain.transaction.filter

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.contravariant._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import cats.{Applicative, Id, Order}

import scala.annotation.tailrec

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object Consecutive {
  def signedTxOrder[T <: Transaction: Order]: Order[Signed[T]] =
    Order.whenEqual(Order.by(-_.fee.value.value), Order[Signed[T]])

  def hashedTxOrder[T <: Transaction: Order]: Order[Hashed[T]] = signedTxOrder.contramap(_.signed)

  def take[F[_]: Async: KryoSerializer, T <: Transaction: Order](txs: List[Signed[T]]): F[List[Signed[T]]] = {
    val headTx =
      txs.sorted(Order.whenEqual(Order.by[Signed[T], Long](_.ordinal.value.value), signedTxOrder).toOrdering).headOption

    headTx match {
      case None => Applicative[F].pure(List.empty)
      case Some(headTx) =>
        TransactionReference
          .of(headTx)
          .flatMap { headTxReference =>
            takeGeneric[F, TransactionReference, Signed[T]](
              signedTx => TransactionReference.of(signedTx),
              _.parent,
              txs.diff(Seq(headTx)),
              headTxReference
            )(Applicative[F], Order[TransactionReference], signedTxOrder).map(_ :+ headTx)
          }
    }
  }

  def take[T <: Transaction: Order](
    txs: List[Hashed[T]],
    lastAcceptedTxRef: TransactionReference
  ): List[Hashed[T]] =
    takeGeneric[Id, TransactionReference, Hashed[T]](
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
