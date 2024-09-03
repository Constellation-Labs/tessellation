package io.constellationnetwork.transaction

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait TransactionGenerator {

  def generateTransactions[F[_]: Async: SecurityProvider](
    src: Address,
    srcKey: KeyPair,
    dst: Address,
    count: PosInt,
    fee: TransactionFee = TransactionFee.zero,
    lastTxRef: Option[TransactionReference] = None,
    txHasher: Hasher[F]
  ): F[NonEmptyList[Hashed[Transaction]]] = {
    implicit val h = txHasher

    def generate(src: Address, srcKey: KeyPair, dst: Address, lastTxRef: TransactionReference): F[Hashed[Transaction]] =
      forAsyncHasher[F, Transaction](
        Transaction(src, dst, TransactionAmount(1L), fee, lastTxRef, TransactionSalt(0L)),
        srcKey
      ).flatMap(_.toHashed[F])

    generate(src, srcKey, dst, lastTxRef.getOrElse(TransactionReference.empty)).flatMap { first =>
      (1 until count).toList.foldLeftM(NonEmptyList.one(first)) {
        case (txs, _) =>
          generate(src, srcKey, dst, TransactionReference(txs.head.ordinal, txs.head.hash)).map(txs.prepend)
      }
    }
  }.map(_.reverse)
}
