package io.constellationnetwork.node.shared.domain.swap.filter

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.contravariant._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import cats.{Applicative, Id, Order}

import scala.annotation.tailrec

import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

object Consecutive {
  def signedTxOrder: Order[Signed[AllowSpend]] =
    Order.whenEqual(Order.by(-_.fee.value.value), Order[Signed[AllowSpend]])

  def allowSpendTxOrder: Order[Hashed[AllowSpend]] = signedTxOrder.contramap(_.signed)

  def take[F[_]: Async](txs: List[Signed[AllowSpend]], txHasher: Hasher[F]): F[List[Signed[AllowSpend]]] = {
    val headTx =
      txs.sorted(Order.whenEqual(Order.by[Signed[AllowSpend], Long](_.ordinal.value.value), signedTxOrder).toOrdering).headOption

    headTx match {
      case None => Applicative[F].pure(List.empty)
      case Some(headTx) =>
        implicit val hasher = txHasher
        AllowSpendReference
          .of(headTx)
          .flatMap { headTxReference =>
            takeGeneric[F, AllowSpendReference, Signed[AllowSpend]](
              signedTx => AllowSpendReference.of(signedTx),
              _.parent,
              txs.diff(Seq(headTx)),
              headTxReference
            )(Applicative[F], Order[AllowSpendReference], signedTxOrder).map(_ :+ headTx)
          }
    }
  }

  def take(
    txs: List[Hashed[AllowSpend]],
    lastAcceptedTxRef: AllowSpendReference
  ): List[Hashed[AllowSpend]] =
    takeGeneric[Id, AllowSpendReference, Hashed[AllowSpend]](
      hashedTx => Id(AllowSpendReference.of(hashedTx)),
      _.parent,
      txs,
      lastAcceptedTxRef
    )(Applicative[Id], Order[AllowSpendReference], allowSpendTxOrder)

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
