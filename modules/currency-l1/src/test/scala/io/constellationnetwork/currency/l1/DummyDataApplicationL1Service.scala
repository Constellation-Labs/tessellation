package io.constellationnetwork.currency.l1

import cats.data.NonEmptyList
import cats.effect.{Async, IO}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.http.Codecs.{feeTransactionRequestDecoder, feeTransactionResponseEncoder}
import io.constellationnetwork.json.JsonBinarySerializer
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Encoder}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoderOf

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

  override def routes(implicit context: L1NodeContext[IO]): HttpRoutes[IO] = ???

  override def routesPrefix: ExternalUrlPrefix = ???

  override def postDataTransactionsRequestDecoder(req: Request[IO])(implicit f: Async[IO]): IO[DataRequest] = {
    implicit val dataUpdateDecoder: Decoder[DataUpdate] = dataDecoder
    implicit val feeTransactionDecoder: Decoder[FeeTransaction] = deriveDecoder[FeeTransaction]

    implicit val requestDecoder: EntityDecoder[IO, DataTransactions] = feeTransactionRequestDecoder
    req
      .as[DataTransactions]
      .map[DataRequest](DataTransactionsRequest)
      .handleErrorWith { _ =>
        implicit val signedEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = signedDataEntityDecoder
        req.as[Signed[DataUpdate]].map[DataRequest] { signedData =>
          SingleDataUpdateRequest(signedData.widen)
        }
      }
  }

  override def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  )(
    implicit f: Async[IO]
  ): IO[Response[IO]] =
    feeTransactionResponseEncoder(dataRequest, validationResult)
}
