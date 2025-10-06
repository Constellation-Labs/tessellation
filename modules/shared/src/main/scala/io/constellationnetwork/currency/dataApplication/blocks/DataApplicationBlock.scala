package io.constellationnetwork.currency.dataApplication.block

import cats.data.NonEmptyList
import cats.kernel.Eq
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash

import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

case class DataApplicationBlock(
  roundId: RoundId,
  dataTransactions: NonEmptyList[DataTransactions],
  dataTransactionsHashes: NonEmptyList[NonEmptyList[Hash]]
) extends Encodable[NonEmptyList[NonEmptyList[Hash]]] {
  override def toEncode: NonEmptyList[NonEmptyList[Hash]] = dataTransactionsHashes
  override def jsonEncoder: Encoder[NonEmptyList[NonEmptyList[Hash]]] = implicitly
}

object DataApplicationBlock {
  implicit def dataTransactionDecoder(implicit d: Decoder[DataUpdate]): Decoder[DataTransaction] = DataTransaction.decoder
  implicit def dataTransactionEncoder(implicit e: Encoder[DataUpdate]): Encoder[DataTransaction] = DataTransaction.encoder

  implicit def decoder(implicit d: Decoder[DataTransaction]): Decoder[DataApplicationBlock] = deriveDecoder
  implicit def encoder(implicit e: Encoder[DataTransaction]): Encoder[DataApplicationBlock] = deriveEncoder

  implicit def eqv: Eq[DataApplicationBlock] =
    Eq.and[DataApplicationBlock](
      Eq[RoundId].contramap(_.roundId),
      Eq[NonEmptyList[NonEmptyList[Hash]]].contramap(_.dataTransactionsHashes)
    )
}
