package org.tessellation.dag.transaction.filter

import scala.annotation.tailrec

import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.security.signature.Signed

object Consecutive {

  def take(
    lastAcceptedTxRef: TransactionReference,
    txs: Seq[Signed[Transaction]]
  ): Seq[Signed[Transaction]] = {
    @tailrec
    def loop(
      acc: Seq[Signed[Transaction]],
      prevTxRef: TransactionReference,
      txs: Seq[Signed[Transaction]]
    ): Seq[Signed[Transaction]] =
      txs.find(_.value.parent == prevTxRef) match {
        case Some(tx) =>
          loop(tx +: acc, tx.value.parent, txs.diff(Seq(tx)))
        case None => acc.reverse //to preserve order of the chain
      }

    loop(Seq.empty, lastAcceptedTxRef, txs)
  }
}
