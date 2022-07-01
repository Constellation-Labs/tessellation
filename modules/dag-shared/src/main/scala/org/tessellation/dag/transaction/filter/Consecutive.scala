package org.tessellation.dag.transaction.filter

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import cats.{Applicative, Order}

import scala.annotation.tailrec

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.Hashed

object Consecutive {

  def take[F[_]: Async: KryoSerializer](
    txs: List[Hashed[Transaction]],
    lastAcceptedTxRef: TransactionReference
  ): F[List[Hashed[Transaction]]] = {
    val hashedTxOrder: Order[Hashed[Transaction]] =
      Order.whenEqual(Order.by(-_.fee.value.value), Order[Hashed[Transaction]])

    takeGeneric[F, TransactionReference, Hashed[Transaction]](
      hashedTx => Applicative[F].pure(TransactionReference.of(hashedTx)),
      _.parent,
      txs,
      lastAcceptedTxRef
    )(Applicative[F], Order[TransactionReference], hashedTxOrder)
  }

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
