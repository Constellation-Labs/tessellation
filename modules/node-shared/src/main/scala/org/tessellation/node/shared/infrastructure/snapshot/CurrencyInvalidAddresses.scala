package org.tessellation.node.shared.infrastructure.snapshot

import cats.syntax.all._

import scala.collection.immutable.SortedMap

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionReference

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object CurrencyInvalidAddresses {
  case class AddressesInvalidAtOrdinal(
    snapshotOrdinal: SnapshotOrdinal,
    filterFunction: (SortedMap[Address, Balance], SortedMap[Address, TransactionReference]) => SortedMap[Address, Balance]
  )

  val metagraphIdMainnet: Address = Address("DAG7ChnhUF7uKgn8tXy45aj4zn9AFuhaZr8VXY43")
  val metagraphIdTestnet: Address = Address("DAG6YFZLJP8eswniw5SBHgjXZLbk6zoUFK5Ec9qr")
  val metagraphsInvalidAddresses: Map[Address, AddressesInvalidAtOrdinal] = Map(
    metagraphIdMainnet -> AddressesInvalidAtOrdinal(
      SnapshotOrdinal(NonNegLong.unsafeFrom(1268980)),
      (currentBalances, currentTransactionsReferences) =>
        currentBalances.filterNot {
          case (address, balance) =>
            balance.value.value === (1L * 1e8).toLong && !currentTransactionsReferences.contains(address)
        }
    ),
    metagraphIdTestnet -> AddressesInvalidAtOrdinal(
      SnapshotOrdinal(NonNegLong.unsafeFrom(15345)),
      (currentBalances, _) =>
        currentBalances.filterNot {
          case (address, _) =>
            address =!= Address("DAG5sz69nNwGF8ypn1yukFpg2pVJpdx5mnf1PJVc")
        }
    )
  )
}
