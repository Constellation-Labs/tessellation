package org.tessellation.infrastructure.dag

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.snapshot.SnapshotOrdinal
import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import io.estatico.newtype.ops._

object DAGService {

  def make[F[_]: Async](globalSnapshotStorage: GlobalSnapshotStorage[F]): DAGService[F] =
    new DAGService[F] {

      def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.flatMap {
          _.flatTraverse(snapshot => getBalance(snapshot.value.ordinal, address))
        }

      def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        globalSnapshotStorage.get(ordinal).map {
          _.map { snapshot =>
            val balance = snapshot.value.info.balances.getOrElse(address, Balance.empty)
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
          }
        }

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.flatMap {
          _.flatTraverse(snapshot => getTotalSupply(snapshot.value.ordinal))
        }

      def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]] = {
        val empty = BigInt(Balance.empty.coerce.value)

        globalSnapshotStorage.get(ordinal).map {
          _.map { snapshot =>
            val supply = snapshot.value.info.balances.values
              .foldLeft(empty) { (acc, b) =>
                acc + BigInt(b.coerce.value)
              }
            val ordinal = snapshot.value.ordinal

            (supply, ordinal)
          }
        }

      }

    }
}
