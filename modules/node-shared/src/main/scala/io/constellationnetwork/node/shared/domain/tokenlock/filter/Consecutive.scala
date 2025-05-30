package io.constellationnetwork.node.shared.domain.tokenlock.filter

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Id, Order}

import scala.annotation.tailrec

import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

object Consecutive {
  def signedTxOrder: Order[Signed[TokenLock]] =
    Order.whenEqual(Order.by(-_.fee.value.value), Order[Signed[TokenLock]])

  def tokenLockTxOrder: Order[Hashed[TokenLock]] = signedTxOrder.contramap(_.signed)

  def take[F[_]: Async](txs: List[Signed[TokenLock]], txHasher: Hasher[F]): F[List[Signed[TokenLock]]] = {
    val headTx =
      txs.sorted(Order.whenEqual(Order.by[Signed[TokenLock], Long](_.ordinal.value.value), signedTxOrder).toOrdering).headOption

    headTx match {
      case None => Applicative[F].pure(List.empty)
      case Some(headTx) =>
        implicit val hasher = txHasher
        TokenLockReference
          .of(headTx)
          .flatMap { headTxReference =>
            takeGeneric[F, TokenLockReference, Signed[TokenLock]](
              signedTx => TokenLockReference.of(signedTx),
              _.parent,
              txs.diff(Seq(headTx)),
              headTxReference
            )(Applicative[F], Order[TokenLockReference], signedTxOrder).map(_ :+ headTx)
          }
    }
  }

  def take(
    txs: List[Hashed[TokenLock]],
    lastAcceptedTxRef: TokenLockReference
  ): List[Hashed[TokenLock]] =
    takeGeneric[Id, TokenLockReference, Hashed[TokenLock]](
      hashedTx => Id(TokenLockReference.of(hashedTx)),
      _.parent,
      txs,
      lastAcceptedTxRef
    )(Applicative[Id], Order[TokenLockReference], tokenLockTxOrder)

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
