package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.{Transaction, TransactionReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection._

trait TransactionReferenceManager[F[_]] {
  def acceptTransactionRefs(
    lastTxRefs: SortedMap[Address, TransactionReference],
    lastTxRefsContextUpdate: Map[Address, TransactionReference],
    acceptedTransactions: SortedSet[Signed[Transaction]]
  ): (SortedMap[Address, TransactionReference], SortedMap[Address, TransactionReference])
}

object TransactionReferenceManager {

  def make[F[_]](): TransactionReferenceManager[F] = new TransactionReferenceManager[F] {

    def acceptTransactionRefs(
      lastTxRefs: SortedMap[Address, TransactionReference],
      lastTxRefsContextUpdate: Map[Address, TransactionReference],
      acceptedTransactions: SortedSet[Signed[Transaction]]
    ): (SortedMap[Address, TransactionReference], SortedMap[Address, TransactionReference]) = {
      val updatedRefs = lastTxRefs ++ lastTxRefsContextUpdate
      val newDestinationAddresses = acceptedTransactions.map(_.destination) -- updatedRefs.keySet
      val newDestinationAddressesRefs = newDestinationAddresses.toList.map(_ -> TransactionReference.empty)

      ((updatedRefs ++ newDestinationAddressesRefs).toSortedMap, (lastTxRefsContextUpdate ++ newDestinationAddressesRefs).toSortedMap)

    }
  }
}
