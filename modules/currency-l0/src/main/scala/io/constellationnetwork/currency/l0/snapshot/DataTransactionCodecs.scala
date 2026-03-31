package io.constellationnetwork.currency.l0.snapshot

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction, DataUpdate}

import io.circe.{Decoder, Encoder, Json}

/** Shared DataTransaction codec derivation.
  *
  * Currency modules can't auto-derive DataTransaction codecs because the DataUpdate encoder/decoder comes from the optional data
  * application. This object centralizes the pattern so it's defined once.
  */
object DataTransactionCodecs {

  def encoder[F[_]](maybeDataApplication: Option[BaseDataApplicationL0Service[F]]): Encoder[DataTransaction] =
    maybeDataApplication.map { da =>
      implicit val duEnc: Encoder[DataUpdate] = da.dataEncoder
      DataTransaction.encoder
    }.getOrElse((_: DataTransaction) => Json.Null)

  def decoder[F[_]](maybeDataApplication: Option[BaseDataApplicationL0Service[F]]): Decoder[DataTransaction] =
    maybeDataApplication.map { da =>
      implicit val duDec: Decoder[DataUpdate] = da.dataDecoder
      DataTransaction.decoder
    }.getOrElse(Decoder.failedWithMessage("DataTransaction decoder not provided"))
}
