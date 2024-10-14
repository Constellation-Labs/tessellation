package io.constellationnetwork.node.shared.infrastructure.snapshot.services

import cats.Applicative
import cats.syntax.functor._

import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.snapshot.services.AddressService
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}

import io.estatico.newtype.ops._

object AddressService {
  def make[F[_]: Applicative, S <: Snapshot, C <: SnapshotInfo[_]](
    addressCfg: AddressesConfig,
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

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) => calculateTotalSupply(state.balances.values, snapshot.value.ordinal)
        })

      def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.size
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        snapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(
              state.balances.filterNot { case (a, _) => addressCfg.locked.contains(a) }.values,
              snapshot.value.ordinal
            )
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
