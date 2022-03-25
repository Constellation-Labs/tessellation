package org.tessellation.infrastructure.dag

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import io.estatico.newtype.ops._

object DAGService {

  def make[F[_]: Async](globalSnapshotStorage: GlobalSnapshotStorage[F]): DAGService[F] =
    new DAGService[F] {

      def getBalance(address: Address): F[Balance] =
        globalSnapshotStorage.head.map {
          case Some(snapshot) => snapshot.value.info.balances.get(address).getOrElse(Balance.empty)
          case None           => Balance.empty
        }

      def getTotalSupply: F[BigInt] = {
        val empty = BigInt(Balance.empty.coerce.value)

        globalSnapshotStorage.head.map {
          case Some(snapshot) =>
            snapshot.value.info.balances.values
              .foldLeft(empty) { (acc, b) =>
                acc + BigInt(b.coerce.value)
              }
          case None => empty
        }
      }

    }
}
