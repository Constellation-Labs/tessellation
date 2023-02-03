package org.tessellation.infrastructure.dag

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.security.signature.Signed

import io.estatico.newtype.ops._

object DAGService {

  def make[F[_]: Async](globalSnapshotStorage: GlobalSnapshotStorage[F]): DAGService[F] =
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

      private def getTotalSupply(snapshot: Signed[GlobalSnapshot]): (BigInt, SnapshotOrdinal) = {
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
