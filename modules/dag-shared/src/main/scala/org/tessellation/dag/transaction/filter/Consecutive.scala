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
import org.tessellation.security.signature.Signed

object Consecutive {

  def take[F[_]: Async: KryoSerializer](
    txs: List[Signed[Transaction]],
    lastAcceptedTxRef: TransactionReference
  ): F[List[Signed[Transaction]]] =
    takeGeneric(
      TransactionReference.of[F],
      _.parent,
      txs,
      lastAcceptedTxRef
    )

  private def takeGeneric[F[_]: Applicative, Ref: Order, Item](
    refOf: Item => F[Ref],
    prevRefOf: Item => Ref,
    items: List[Item],
    initRef: Ref
  ): F[List[Item]] =
    items.traverse { item =>
      refOf(item).map(_ -> item)
    }.map { refItemPairs =>
      val grouped = refItemPairs.groupByNel { case (_, item) => prevRefOf(item) }

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
