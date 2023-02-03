package org.tessellation.dag.l1

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait TransactionGenerator {

  def generateTransactions[F[_]: Async: KryoSerializer: SecurityProvider](
    src: Address,
    srcKey: KeyPair,
    dst: Address,
    count: PosInt,
    fee: TransactionFee = TransactionFee.zero,
    lastTxRef: Option[TransactionReference] = None
  ): F[NonEmptyList[Hashed[Transaction]]] = {
    def generate(src: Address, srcKey: KeyPair, dst: Address, lastTxRef: TransactionReference): F[Hashed[Transaction]] =
      forAsyncKryo[F, Transaction](
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
