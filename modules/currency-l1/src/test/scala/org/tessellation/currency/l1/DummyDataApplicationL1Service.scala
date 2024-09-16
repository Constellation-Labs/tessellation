package org.tessellation.currency.l1

import cats.Applicative
import cats.effect.IO

import org.tessellation.currency.dataApplication._
import org.tessellation.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import org.tessellation.json.JsonBinarySerializer
import org.tessellation.routes.internal.ExternalUrlPrefix
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.signature.Signed

import io.circe.{Decoder, Encoder}
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoderOf
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}

class DummyDataApplicationL1Service extends BaseDataApplicationL1Service[IO] {
  override def serializeState(state: DataOnChainState): IO[Array[Byte]] = ???

  override def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, DataOnChainState]] = ???

  override def serializeUpdate(update: DataUpdate): IO[Array[Byte]] = IO.delay(JsonBinarySerializer.serialize(update)(dataEncoder))

  override def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, DataUpdate]] = ???

  override def serializeBlock(block: Signed[DataApplicationBlock]): IO[Array[Byte]] = ???

  override def deserializeBlock(bytes: Array[Byte]): IO[Either[Throwable, Signed[DataApplicationBlock]]] = ???

  override def serializeCalculatedState(state: DataCalculatedState): IO[Array[Byte]] = ???

  override def deserializeCalculatedState(bytes: Array[Byte]): IO[Either[Throwable, DataCalculatedState]] = ???

  override def dataEncoder: Encoder[DataUpdate] = DummyDataApplicationState.dataUpateEncoder

  override def dataDecoder: Decoder[DataUpdate] = DummyDataApplicationState.dataUpdateDecoder

  override def signedDataEntityEncoder: EntityEncoder[IO, Signed[DataUpdate]] =
    jsonEncoderOf[IO, Signed[DataUpdate]](Signed.encoder[DataUpdate](dataEncoder))

  override def signedDataEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = {
    implicit val signedDecoder: Decoder[Signed[DataUpdate]] = Signed.decoder[DataUpdate](dataDecoder)
    circeEntityDecoder
  }

  override def calculatedStateEncoder: Encoder[DataCalculatedState] = ???

  override def calculatedStateDecoder: Decoder[DataCalculatedState] = ???

  override def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[IO]): IO[DataApplicationValidationErrorOr[Unit]] = ???

  override def validateFee(gsOrdinal: SnapshotOrdinal)(update: Signed[DataUpdate])(
    implicit context: L1NodeContext[IO],
    A: Applicative[IO]
  ): IO[dataApplication.DataApplicationValidationErrorOr[Unit]] = ???

  override def routes(implicit context: L1NodeContext[IO]): HttpRoutes[IO] = ???

  override def routesPrefix: ExternalUrlPrefix = ???
}
