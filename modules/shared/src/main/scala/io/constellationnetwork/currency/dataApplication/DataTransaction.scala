package io.constellationnetwork.currency.dataApplication

import cats.Order
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.http.Codecs._
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

sealed trait DataTransaction

object DataTransaction {
  type DataTransactions = NonEmptyList[Signed[DataTransaction]]

  implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[DataTransaction] =
    Encoder.instance {
      case v: FeeTransaction => v.asJson
      case v: DataUpdate     => e.apply(v)
    }

  implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[DataTransaction] =
    (c: HCursor) => Decoder[FeeTransaction].apply(c).orElse(d.apply(c))

  def collectTransactions[A <: DataTransaction](dataTransactions: List[DataTransactions])(
    pf: PartialFunction[DataTransaction, A]
  ): List[Signed[A]] =
    dataTransactions.flatMap { currentDataTransactions =>
      currentDataTransactions.collect { dataTransaction =>
        dataTransaction.value match {
          case transaction if pf.isDefinedAt(transaction) =>
            Signed(pf(transaction), dataTransaction.proofs)
        }
      }
    }

  def getHashes[F[_]: Async: Hasher](
    data: List[DataTransactions],
    serializeDataUpdate: DataUpdate => F[Array[Byte]]
  )(implicit jsonSerializer: JsonSerializer[F]): F[List[NonEmptyList[hash.Hash]]] =
    data.traverse { dataTransactions =>
      dataTransactions.toList.traverse { signedTransaction =>
        signedTransaction.value match {
          case dataUpdate: DataUpdate =>
            Signed(dataUpdate, signedTransaction.proofs)
              .toHashed(serializeDataUpdate)
              .map(_.hash)
          case feeTransaction: FeeTransaction =>
            Signed(feeTransaction, signedTransaction.proofs)
              .toHashed(FeeTransaction.serialize[F])
              .map(_.hash)
        }
      }.map(NonEmptyList.fromListUnsafe)
    }
}

trait DataUpdate extends DataTransaction

object DataUpdate {
  def getDataUpdates(dataTransactions: List[DataTransactions]): List[Signed[DataUpdate]] =
    DataTransaction.collectTransactions(dataTransactions) {
      case dataUpdate: DataUpdate => dataUpdate
    }
}

@derive(decoder, encoder, ordering, show)
case class FeeTransaction(
  source: Address,
  destination: Address,
  amount: Amount,
  dataUpdateRef: Hash
) extends DataTransaction

object FeeTransaction {
  implicit val orderFeeTransaction: Order[FeeTransaction] = Order.by(_.amount)

  def serialize[F[_]: Async](feeTransaction: FeeTransaction)(implicit jsonSerializer: JsonSerializer[F]): F[Array[Byte]] =
    jsonSerializer.serialize(feeTransaction)

  def getFeeTransactions(dataTransactions: List[DataTransactions]): List[Signed[FeeTransaction]] =
    DataTransaction.collectTransactions(dataTransactions) {
      case feeTransaction: FeeTransaction => feeTransaction
    }

  def getByDataUpdate[F[_]: Async](
    dataTransactions: DataTransactions,
    dataUpdate: DataUpdate,
    serializeDataUpdate: DataUpdate => F[Array[Byte]]
  ): F[Option[Signed[FeeTransaction]]] = {
    val feeTransactions = dataTransactions.collect {
      case Signed(feeTransaction: FeeTransaction, proofs) => Signed(feeTransaction, proofs)
    }

    serializeDataUpdate(dataUpdate).map { serializedDataUpdate =>
      feeTransactions.find { feeTransaction =>
        Hash.fromBytes(serializedDataUpdate) === feeTransaction.value.dataUpdateRef
      }
    }
  }
}
