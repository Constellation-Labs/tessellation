package org.tessellation.wallet

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.crypto._
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

package object transaction {

  def createTransaction[F[_]: Async: Hasher: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    prevTx: Option[Signed[Transaction]],
    fee: TransactionFee,
    amount: TransactionAmount
  ): F[Signed[Transaction]] =
    for {
      source <- keyPair.getPublic.toAddress.pure[F]

      parent <- prevTx
        .map(_.value)
        .map(tx => tx.hash.map(TransactionReference(tx.ordinal, _)))
        .getOrElse(TransactionReference.empty.pure[F])

      salt <- TransactionSalt.generate

      tx = Transaction(source, destination, amount, fee, parent, salt)
      signedTx <- tx.sign(keyPair)

    } yield signedTx
}
