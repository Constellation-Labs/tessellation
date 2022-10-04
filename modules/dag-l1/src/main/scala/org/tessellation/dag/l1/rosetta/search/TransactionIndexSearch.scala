package org.tessellation.dag.l1.rosetta.search

import cats.effect.MonadCancelThrow

import org.tessellation.dag.l1.domain.transaction.storage.{SignedTransactionIndexEntry, TransactionIndexStorage}
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.rosetta.api.model.BlockSearchRequest
import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.security.hash.Hash

import eu.timepit.refined.refineV

object TransactionIndexSearch {

  def make[F[_]: MonadCancelThrow: Database: KryoSerializer](
    storage: TransactionIndexStorage[F]
  ): TransactionIndexSearch[F] =
    new TransactionIndexSearch[F](store = storage) {

      def searchTransactions(request: BlockSearchRequest) = {
        val transactionHash = request.transactionHash.map(x => Hash(x))
        val address =
          request.addressOpt.flatMap(x => refineV[DAGAddressRefined].apply[String](x).map(y => Address(y)).toOption)
        val networkStatus = request.networkStatus.flatMap(x => NetworkStatus.fromString(x))
        val offset = request.offset.flatMap(x => {
          if (x > Int.MaxValue || x < Int.MinValue)
            Left("The offset is invalid.")
          else
            Right(x.toInt)
        }.toOption)
        val limit = request.limit.flatMap(x => {
          if (x > Int.MaxValue || x < Int.MinValue)
            Left("The limit is invalid.")
          else
            Right(x.toInt)
        }.toOption)

        if (request.isOr)
          store.getTransactionIndexValuesOr(transactionHash, address, networkStatus, request.maxBlock, offset, limit)
        else {
          store.getTransactionIndexValuesAnd(transactionHash, address, networkStatus, request.maxBlock, offset, limit)
        }
      }
    }
}

sealed abstract class TransactionIndexSearch[F[_]] private (
  val store: TransactionIndexStorage[F]
) {
  def searchTransactions(request: BlockSearchRequest): Either[String, F[List[SignedTransactionIndexEntry]]]
}
