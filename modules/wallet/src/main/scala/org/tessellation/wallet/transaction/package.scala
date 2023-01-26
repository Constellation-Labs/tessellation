package org.tessellation.wallet

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.security.{SecureRandom, SecurityProvider}

package object transaction {

  def createTransaction[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    prevTx: Option[Signed[DAGTransaction]],
    fee: TransactionFee,
    amount: TransactionAmount
  ): F[Signed[DAGTransaction]] =
    for {
      source <- keyPair.getPublic.toAddress.pure[F]

      parent <- prevTx
        .map(_.value)
        .map(tx => tx.hashF.map(TransactionReference(tx.ordinal, _)))
        .getOrElse(TransactionReference.empty.pure[F])

      salt <- SecureRandom
        .get[F]
        .map(_.nextLong())
        .map(TransactionSalt.apply)

      tx = DAGTransaction(source, destination, amount, fee, parent, salt)
      signedTx <- tx.sign(keyPair)

    } yield signedTx
}
