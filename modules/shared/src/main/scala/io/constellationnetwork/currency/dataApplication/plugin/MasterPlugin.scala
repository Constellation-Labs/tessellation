package io.constellationnetwork.currency.dataApplication.plugin

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.http.Codecs.{dataTransactionsDecoder, feeTransactionResponseEncoder}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import io.circe._
import io.circe.generic.semiauto.deriveDecoder
import org.http4s._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait MasterPlugin[
  F[_],
  PUpdate <: DataUpdate,
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
] extends MetagraphPlugin[F, PUpdate, POnChain, PCalculated] {
  def genesisState: DataState[POnChain, PCalculated]

  def serializeState(state: POnChain): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, POnChain]]

  def serializeUpdate(update: PUpdate): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, PUpdate]]

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]

  def serializeCalculatedState(state: PCalculated): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, PCalculated]]

  def updateEncoder: Encoder[PUpdate]
  def updateDecoder: Decoder[PUpdate]
  def signedUpdateEntityDecoder: EntityDecoder[F, Signed[PUpdate]]
  def calculatedStateEncoder: Encoder[PCalculated]
  def calculatedStateDecoder: Decoder[PCalculated]
  def onChainStateEncoder: Encoder[POnChain]
  def onChainStateDecoder: Decoder[POnChain]

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]): F[Unit] =
    A.unit

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo)(implicit A: Applicative[F]): F[Unit] =
    A.unit

  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], async: Async[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] =
    Async[F].pure(SortedSet.empty[TokenUnlock])

  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, PCalculated)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: PCalculated)(implicit context: L0NodeContext[F]): F[Boolean]

  def hashCalculatedState(state: PCalculated)(implicit context: L0NodeContext[F]): F[Hash]

  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: PUpdate)(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[EstimatedFee] = EstimatedFee.empty.pure[F]

  def postDataTransactionsRequestDecoder(req: Request[F])(implicit F: Async[F]): F[DataRequest] = {
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    for {
      bytes <- req.body.compile.to(Array)
      result <- decodeRequestBody(bytes, logger)
    } yield result
  }

  private def decodeRequestBody(bytes: Array[Byte], logger: Logger[F])(implicit F: Async[F]): F[DataRequest] = {
    implicit val dataUpdateDecoder: Decoder[PUpdate] = updateDecoder
    implicit val feeTransactionDecoder: Decoder[FeeTransaction] = deriveDecoder[FeeTransaction]
    implicit val dtDecoders: EntityDecoder[F, DataTransactions] = dataTransactionsDecoder

    val bodyStream = Stream.emits(bytes).covary[F]
    Request(body = bodyStream).attemptAs[DataTransactions].value.flatMap {
      case Right(dataTransactions) =>
        (DataTransactionsRequest(dataTransactions): DataRequest).pure[F]

      case Left(firstFailure) =>
        implicit val signedEntityDecoder: EntityDecoder[F, Signed[PUpdate]] = signedUpdateEntityDecoder
        Request(body = Stream.emits(bytes).covary[F])
          .attemptAs[Signed[PUpdate]]
          .value
          .flatMap {
            case Right(signedData) =>
              (SingleDataUpdateRequest(signedData.widen[DataUpdate]): DataRequest).pure[F]

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
  )(implicit f: Async[F]): F[Response[F]] =
    feeTransactionResponseEncoder(dataRequest, validationResult)
}
