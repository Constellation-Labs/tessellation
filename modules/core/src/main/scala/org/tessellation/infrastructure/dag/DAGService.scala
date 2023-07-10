package org.tessellation.infrastructure.dag

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.snapshot.SnapshotStorage
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.domain.transaction.TransactionValidator.lockedAddresses
import org.tessellation.security.signature.Signed

import io.estatico.newtype.ops._

object DAGService {

  def make[F[_]: Async](globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshot]): DAGService[F] =
    new DAGService[F] {
      private def getBalance(snapshot: Signed[GlobalSnapshot], address: Address): (Balance, SnapshotOrdinal) = {
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

      private def calculateTotalSupply(balances: Iterable[Balance], ordinal: SnapshotOrdinal): (BigInt, SnapshotOrdinal) = {
        val empty = BigInt(Balance.empty.coerce.value)
        val supply = balances
          .foldLeft(empty) { (acc, b) =>
            acc + BigInt(b.coerce.value)
          }

        (supply, ordinal)
      }

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map {
          _.map(s => calculateTotalSupply(s.info.balances.values, s.ordinal))
        }

      def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map { maybeSignedSnapshot =>
          maybeSignedSnapshot.map { snapshot =>
            calculateTotalSupply(snapshot.info.balances.filterNot { case (a, _) => lockedAddresses.contains(a) }.values, snapshot.ordinal)
          }
        }

      def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.get(ordinal).map {
          _.map(s => calculateTotalSupply(s.info.balances.values, s.ordinal))
        }

      private def getWalletCount(snapshot: Signed[GlobalSnapshot]): (Int, SnapshotOrdinal) = {
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
