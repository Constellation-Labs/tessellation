package io.constellationnetwork.currency.dataApplication.services

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.ops.DataApplicationL1ContextualOps
import io.constellationnetwork.currency.dataApplication.plugin.PluginRegistry
import io.constellationnetwork.currency.http.Codecs.{dataTransactionsDecoder, feeTransactionResponseEncoder}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe._
import io.circe.generic.semiauto.deriveDecoder
import org.http4s._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationL1Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationService[F, D, DON, DOF]
    with DataApplicationL1ContextualOps[F, D, DON, DOF] {

  def postDataTransactionsRequestDecoder(req: Request[F])(implicit F: Async[F]): F[DataRequest] = {
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    for {
      bytes <- req.body.compile.to(Array)
      result <- decodeRequestBody(bytes, logger)
    } yield result
  }

  private def decodeRequestBody(bytes: Array[Byte], logger: Logger[F])(implicit F: Async[F]): F[DataRequest] = {
    implicit val dataUpdateDecoder: Decoder[D] = dataDecoder
    implicit val feeTransactionDecoder: Decoder[FeeTransaction] = deriveDecoder[FeeTransaction]
    implicit val dtDecoders: EntityDecoder[F, DataTransactions] = dataTransactionsDecoder

    val bodyStream = Stream.emits(bytes).covary[F]
    Request(body = bodyStream).attemptAs[DataTransactions].value.flatMap {
      case Right(dataTransactions) =>
        (DataTransactionsRequest(dataTransactions): DataRequest).pure[F]

      case Left(firstFailure) =>
        implicit val signedEntityDecoder: EntityDecoder[F, Signed[D]] = signedDataEntityDecoder
        Request(body = Stream.emits(bytes).covary[F])
          .attemptAs[Signed[D]]
          .value
          .flatMap {
            case Right(signedData) =>
              (SingleDataUpdateRequest(signedData.widen): DataRequest).pure[F]

            case Left(secondFailure) =>
              logger.error(s"Decoding failed for both types: $firstFailure, $secondFailure") >>
                F.raiseError[DataRequest](
                  InvalidMessageBodyFailure(
                    s"Could not decode as DataTransactions or Signed data: ${firstFailure.message}"
                  )
                )
          }
    }
  }

  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  )(
    implicit f: Async[F]
  ): F[Response[F]] =
    feeTransactionResponseEncoder(dataRequest, validationResult)

  def pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] = None
}
