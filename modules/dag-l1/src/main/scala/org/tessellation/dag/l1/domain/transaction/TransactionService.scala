package org.tessellation.dag.l1.domain.transaction

import cats.data.NonEmptyList

import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

trait TransactionService[F[_]] {
  def isParentAccepted(tx: Transaction): F[Boolean]
  def getLastAcceptedReference(address: Address): F[TransactionReference]
  def accept(hashedTx: Hashed[Transaction]): F[Unit]
  def put(transactions: Set[Signed[Transaction]]): F[Unit]
  def pull(): F[Option[NonEmptyList[Signed[Transaction]]]]
}
