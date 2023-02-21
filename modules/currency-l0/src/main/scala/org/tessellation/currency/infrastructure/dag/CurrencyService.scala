package org.tessellation.currency.infrastructure.dag

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.currency.domain.dag.CurrencyService
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.security.signature.Signed

import io.estatico.newtype.ops._

object CurrencyService {

  def make[F[_]: Async](globalSnapshotStorage: SnapshotStorage[F, CurrencySnapshot]): CurrencyService[F] =
    new CurrencyService[F] {

      private def getBalance(snapshot: Signed[CurrencySnapshot], address: Address): (Balance, SnapshotOrdinal) = {
        val balance = snapshot.value.info.balances.getOrElse(address, Balance.empty)
        val ordinal = snapshot.value.ordinal

        (balance, ordinal)
      }

      def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map {
          _.map(getBalance(_, address))
        }

      def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        globalSnapshotStorage.get(ordinal).map {
          _.map(getBalance(_, address))
        }

      private def getTotalSupply(snapshot: Signed[CurrencySnapshot]): (BigInt, SnapshotOrdinal) = {
        val empty = BigInt(Balance.empty.coerce.value)
        val supply = snapshot.value.info.balances.values
          .foldLeft(empty) { (acc, b) =>
            acc + BigInt(b.coerce.value)
          }
        val ordinal = snapshot.value.ordinal

        (supply, ordinal)
      }

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map {
          _.map(getTotalSupply)
        }

      def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.get(ordinal).map {
          _.map(getTotalSupply)
        }

      private def getWalletCount(snapshot: Signed[CurrencySnapshot]): (Int, SnapshotOrdinal) = {
        val wallets = snapshot.value.info.balances.size
        val ordinal = snapshot.value.ordinal

        (wallets, ordinal)
      }

      def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map {
          _.map(getWalletCount)
        }

      def getWalletCount(ordinal: SnapshotOrdinal): F[Option[(Int, SnapshotOrdinal)]] =
        globalSnapshotStorage.get(ordinal).map {
          _.map(getWalletCount)
        }

    }
}
