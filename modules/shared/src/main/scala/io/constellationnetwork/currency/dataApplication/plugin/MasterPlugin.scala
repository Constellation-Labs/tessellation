package io.constellationnetwork.currency.dataApplication.plugin

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.circe._
import org.http4s._

trait MasterPlugin[
  F[_],
  U <: DataUpdate,
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
] extends MetagraphPlugin[F, POnChain, PCalculated] {

  // Serialization methods
  def serializeState(state: POnChain): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, POnChain]]

  def serializeUpdate(update: U): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, U]]

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]

  def serializeCalculatedState(state: PCalculated): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, PCalculated]]

  // Encoders/Decoders
  def updateEncoder: Encoder[U]
  def updateDecoder: Decoder[U]
  def signedUpdateEntityDecoder: EntityDecoder[F, Signed[U]]
  def calculatedStateEncoder: Encoder[PCalculated]
  def calculatedStateDecoder: Decoder[PCalculated]
  def onChainStateEncoder: Encoder[POnChain]
  def onChainStateDecoder: Decoder[POnChain]

  // L0 Lifecycle callbacks
  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]): F[Unit] =
    A.unit

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo)(implicit A: Applicative[F]): F[Unit] =
    A.unit

  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], async: Async[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] =
    Async[F].pure(SortedSet.empty[TokenUnlock])

  // L0 Calculated state management
  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, PCalculated)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: PCalculated)(implicit context: L0NodeContext[F]): F[Boolean]

  def hashCalculatedState(state: PCalculated)(implicit context: L0NodeContext[F]): F[Hash]

  // L1 Fee estimation
  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: U)(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[EstimatedFee] = EstimatedFee.empty.pure[F]

  // L1 Request/Response handling
  def postDataTransactionsRequestDecoder(req: Request[F])(implicit F: Async[F]): F[DataRequest]

  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, cats.data.NonEmptyList[Hashed[DataTransaction]]]
  )(implicit f: Async[F]): F[Response[F]]
}
