package org.tessellation.dag.l1.infrastructure.transaction.storage

import cats.effect.MonadCancelThrow
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.domain.transaction.storage.{
  SignedTransactionIndexEntry,
  StoredTransactionIndex,
  TransactionIndexStorage
}
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import doobie.implicits._
import doobie.quill.DoobieContext
import io.getquill.context.Context
import io.getquill.{Literal, SqliteDialect}

object TransactionIndexDbStorage {

  type Ctx = DoobieContext.SQLite[Literal] with Context[SqliteDialect, Literal]

  def make[F[_]: MonadCancelThrow: Database: KryoSerializer]: TransactionIndexStorage[F] =
    make[F](new DoobieContext.SQLite[Literal](Literal))

  def make[F[_]: MonadCancelThrow: KryoSerializer](ctx: Ctx)(implicit db: Database[F]): TransactionIndexStorage[F] =
    new TransactionIndexStorage[F] {

      val xa = db.xa

      import ctx._

      val getStoredTransactionIndex = quote {
        querySchema[StoredTransactionIndex]("TransactionIndex")
      }

      val getStoredTransactionIndexById = quote { (id: Hash) =>
        getStoredTransactionIndex.filter(_.hash == id).take(1)
      }

      val getStoredTransactionIndexWithAnd = quote {
        (
          id: Option[Hash],
          address: Option[Address],
          networkStatus: Option[NetworkStatus],
          height: Option[Long],
          offset: Int,
          limit: Int
        ) =>
          getStoredTransactionIndex
            .filter(
              x =>
                id.forall(_ == x.hash) && address
                  .forall(y => y == x.sourceAddress || y == x.destinationAddress) && networkStatus.forall(
                  _ == x.networkStatus
                ) && height.forall(_ >= x.height)
            )
            .drop(offset)
            .take(limit)
      }

      val getStoredTransactionIndexWithOr = quote {
        (
          id: Option[Hash],
          address: Option[Address],
          networkStatus: Option[NetworkStatus],
          height: Option[Long],
          offset: Int,
          limit: Int
        ) =>
          getStoredTransactionIndex
            .filter(
              x =>
                (id.forall(_ == x.hash) && id.isDefined) || (address.forall(
                  y => y == x.sourceAddress || y == x.destinationAddress
                ) && address.isDefined) || (networkStatus.forall(
                  _ == x.networkStatus
                ) && networkStatus.isDefined) || (height.forall(_ >= x.height) && height.isDefined)
            )
            .drop(offset)
            .take(limit)
      }

      val insertStoredTransactionIndex = quote {
        (
          id: Hash,
          sourceAddress: Address,
          destinationAddress: Address,
          height: Long,
          networkStatus: NetworkStatus,
          transactionBytes: Array[Byte]
        ) =>
          getStoredTransactionIndex.insert(
            _.hash -> id,
            _.sourceAddress -> sourceAddress,
            _.destinationAddress -> destinationAddress,
            _.height -> height,
            _.networkStatus -> networkStatus,
            _.transactionBytes -> transactionBytes
          )
      }

      val updateStoredTransactionIndex = quote {
        (
          id: Hash,
          sourceAddress: Address,
          destinationAddress: Address,
          height: Long,
          networkStatus: NetworkStatus,
          transactionBytes: Array[Byte]
        ) =>
          getStoredTransactionIndex.update(
            _.hash -> id,
            _.sourceAddress -> sourceAddress,
            _.destinationAddress -> destinationAddress,
            _.height -> height,
            _.networkStatus -> networkStatus,
            _.transactionBytes -> transactionBytes
          )
      }

      def updateStoredTransactionIndexValues(
        values: Map[Hash, (Address, Address, Height, NetworkStatus, Array[Byte])]
      ) =
        values.toList.traverse {
          case (hash, moreIndices) =>
            val sourceAddress = moreIndices._1
            val destinationAddress = moreIndices._2
            val height = moreIndices._3
            val networkStatus = moreIndices._4
            val serializedTransaction = moreIndices._5

            run(getStoredTransactionIndexById(lift(hash)))
              .map(_.headOption)
              .flatMap {
                case Some(_) =>
                  run(
                    updateStoredTransactionIndex(
                      lift(hash),
                      lift(sourceAddress),
                      lift(destinationAddress),
                      lift(height.value.value),
                      lift(networkStatus),
                      lift(serializedTransaction)
                    )
                  )
                case None =>
                  run(
                    insertStoredTransactionIndex(
                      lift(hash),
                      lift(sourceAddress),
                      lift(destinationAddress),
                      lift(height.value.value),
                      lift(networkStatus),
                      lift(serializedTransaction)
                    )
                  )
              }
              .transact(xa)
              .as(())
        }.map(x => x.reduce((y, _) => y))

      def getTransactionIndexValuesAnd(
        id: Option[Hash],
        address: Option[Address],
        networkStatus: Option[NetworkStatus],
        maxHeight: Option[Long],
        offset: Option[Int],
        limit: Option[Int]
      ): Either[String, F[List[SignedTransactionIndexEntry]]] = {
        if (offset.isDefined && limit.isEmpty)
          return Left("Limit must be defined if offset is defined.")

        val baseQuery = quote(
          getStoredTransactionIndex.filter(
            t =>
              lift(id).forall(_ == t.hash) && lift(address)
                .forall(x => x == t.sourceAddress || x == t.destinationAddress) && lift(
                networkStatus
              ).forall(
                _ == t.networkStatus
              ) && lift(maxHeight).forall(_ >= t.height)
          )
        )
        val intermediateQuery = offset.map(o => quote(baseQuery.drop(lift(o)))).getOrElse(baseQuery)
        val finalQuery = limit.map(l => quote(intermediateQuery.take(lift(l)))).getOrElse(intermediateQuery)

        val queryResults = run(finalQuery).transact(xa).map(finalizeQueryResults(_))
        Right(queryResults)
      }

      def getTransactionIndexValuesOr(
        id: Option[Hash],
        address: Option[Address],
        networkStatus: Option[NetworkStatus],
        maxHeight: Option[Long],
        offset: Option[Int],
        limit: Option[Int]
      ): Either[String, F[List[SignedTransactionIndexEntry]]] = {
        if (offset.isDefined && limit.isEmpty)
          return Left("Limit must be defined if offset is defined.")

        val baseQuery = quote(
          getStoredTransactionIndex
            .filter(
              x =>
                (lift(id).forall(_ == x.hash) && lift(id).isDefined) || (lift(address).forall(
                  y => y == x.sourceAddress || y == x.destinationAddress
                ) && lift(address).isDefined) || (lift(networkStatus).forall(
                  _ == x.networkStatus
                ) && lift(networkStatus).isDefined) || (lift(maxHeight)
                  .forall(_ >= x.height) && lift(maxHeight).isDefined)
            )
        )
        val intermediateQuery = offset.map(o => quote(baseQuery.drop(lift(o)))).getOrElse(baseQuery)
        val finalQuery = limit.map(l => quote(intermediateQuery.take(lift(l)))).getOrElse(intermediateQuery)

        val queryResults = run(finalQuery).transact(xa).map(finalizeQueryResults(_))
        Right(queryResults)
      }

      private def finalizeQueryResults(queriedTransactions: List[StoredTransactionIndex]) =
        queriedTransactions.flatMap(
          queriedTransaction =>
            KryoSerializer[F]
              .deserialize[Signed[Transaction]](queriedTransaction.transactionBytes)
              .map(x => SignedTransactionIndexEntry(x, queriedTransaction.height, queriedTransaction.networkStatus))
              .toOption
        )
    }
}
