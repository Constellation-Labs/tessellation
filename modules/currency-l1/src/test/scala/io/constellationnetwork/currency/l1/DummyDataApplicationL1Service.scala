package io.constellationnetwork.currency.l1

import cats.data.NonEmptyList
import cats.effect.{Async, IO}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.http.Codecs.{dataTransactionsDecoder, feeTransactionResponseEncoder}
import io.constellationnetwork.json.JsonBinarySerializer
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Encoder}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoderOf
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
    val logger = Slf4jLogger.getLoggerFromName[IO]("DataApplicationL1Service")

    for {
      _ <- logger.debug(s"Attempting to decode incoming request: ${req.method} ${req.uri}")
      bytes <- req.body.compile.to(Array)
      result <- decodeRequestBody(bytes, logger)
    } yield result
  }

  private def decodeRequestBody(bytes: Array[Byte], logger: Logger[IO]): IO[DataRequest] = {
    implicit val dataUpdateDecoder: Decoder[DataUpdate] = dataDecoder
    implicit val feeTransactionDecoder: Decoder[FeeTransaction] = deriveDecoder[FeeTransaction]
    implicit val dtDecoders: EntityDecoder[IO, DataTransactions] = dataTransactionsDecoder

    val bodyStream = Stream.emits(bytes).covary[IO]
    Request(body = bodyStream).attemptAs[DataTransactions].value.flatMap {
      case Right(dataTransactions) =>
        (DataTransactionsRequest(dataTransactions): DataRequest).pure[IO]

      case Left(firstFailure) =>
        implicit val signedEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = signedDataEntityDecoder
        Request(body = Stream.emits(bytes).covary[IO])
          .attemptAs[Signed[DataUpdate]]
          .value
          .flatMap {
            case Right(signedData) =>
              (SingleDataUpdateRequest(signedData.widen): DataRequest).pure[IO]

            case Left(secondFailure) =>
              logger.error(s"Decoding failed for both types: $firstFailure, $secondFailure") >>
                IO.raiseError[DataRequest](
                  InvalidMessageBodyFailure(
                    s"Could not decode as DataTransactions or Signed data: ${firstFailure.message}"
                  )
                )
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
