package org.tessellation.node.shared.infrastructure.snapshot.services

import cats.Applicative
import cats.syntax.functor._

import org.tessellation.node.shared.domain.snapshot.services.AddressService
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.domain.transaction.TransactionValidator.lockedAddresses
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}

import io.estatico.newtype.ops._

object AddressService {
  def make[F[_]: Applicative, S <: Snapshot, C <: SnapshotInfo[_]](
    snapshotStorage: SnapshotStorage[F, S, C]
  ): AddressService[F, S] =
    new AddressService[F, S] {

      def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.getOrElse(address, Balance.empty)
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      // INFO: No historical balances can be red from the current state, hence None returned to keep backward compatibility
      def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        Applicative[F].pure(None)

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) => calculateTotalSupply(state.balances.values, snapshot.value.ordinal)
        })

      // INFO: No historical balances can be red from the current state, hence None returned to keep backward compatibility
      def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]] =
        Applicative[F].pure(None)

      def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.size
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      // INFO: No historical balances can be red from the current state, hence None returned to keep backward compatibility
      def getWalletCount(ordinal: SnapshotOrdinal): F[Option[(Int, SnapshotOrdinal)]] =
        Applicative[F].pure(None)

      def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(state.balances.filterNot { case (a, _) => lockedAddresses.contains(a) }.values, snapshot.value.ordinal)
        })

      private def calculateTotalSupply(balances: Iterable[Balance], ordinal: SnapshotOrdinal): (BigInt, SnapshotOrdinal) = {
        val empty = BigInt(Balance.empty.coerce.value)
        val supply = balances
          .foldLeft(empty) { (acc, b) =>
            acc + BigInt(b.coerce.value)
          }

        (supply, ordinal)
      }
    }
}
