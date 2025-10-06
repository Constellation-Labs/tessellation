package io.constellationnetwork.currency.dataApplication.plugin

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{signature, _}

import io.circe._
import org.http4s._

sealed trait MasterPluginWrapper[F[_]] extends PluginWrapper[F] {
  // Serialization
  def serializeState(state: DataOnChainState): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]]
  def serializeUpdate(update: DataUpdate): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]]
  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]
  def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]]

  // Encoders/Decoders
  def updateEncoder: Encoder[DataUpdate]
  def updateDecoder: Decoder[DataUpdate]
  def signedUpdateEntityDecoder: EntityDecoder[F, Signed[DataUpdate]]
  def calculatedStateEncoder: Encoder[DataCalculatedState]
  def calculatedStateDecoder: Decoder[DataCalculatedState]
  def onChainStateEncoder: Encoder[DataOnChainState]
  def onChainStateDecoder: Decoder[DataOnChainState]

  // L0 Lifecycle
  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]): F[Unit]
  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo)(implicit A: Applicative[F]): F[Unit]
  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]]

  // Calculated state management
  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)]
  def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean]
  def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash]

  // L1 specific
  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: DataUpdate)(implicit context: L1NodeContext[F], A: Applicative[F]): F[EstimatedFee]
  def postDataTransactionsRequestDecoder(req: Request[F]): F[DataRequest]
  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  ): F[Response[F]]
}

class MasterPluginWrapperImpl[
  F[_]: Async,
  U <: DataUpdate,
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
](
  val masterPlugin: MasterPlugin[F, U, POnChain, PCalculated],
  private var state: DataState[POnChain, PCalculated]
) extends MasterPluginWrapper[F] {

  def name: String = masterPlugin.name
  def handles(update: DataUpdate): Boolean = masterPlugin.handles(update)

  def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]) =
    if (handles(update)) masterPlugin.lifecycle.validateUpdate(update)
    else {
      import cats.data.Validated
      Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
    }

  def validateData(updates: NonEmptyList[Signed[DataUpdate]])(implicit context: L0NodeContext[F]) = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    NonEmptyList.fromList(relevantUpdates) match {
      case Some(nel) => masterPlugin.lifecycle.validateData(state, nel)
      case None =>
        import cats.data.Validated
        Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
    }
  }

  def combine(updates: List[Signed[DataUpdate]])(implicit context: L0NodeContext[F]) = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    if (relevantUpdates.isEmpty) Async[F].unit
    else {
      masterPlugin.lifecycle.combine(state, relevantUpdates).flatMap { newState =>
        Async[F].delay { state = newState }
      }
    }
  }

  def extractFees(updates: Seq[Signed[DataUpdate]])(implicit context: L0NodeContext[F]) = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    if (relevantUpdates.isEmpty) Seq.empty[Signed[FeeTransaction]].pure[F]
    else masterPlugin.lifecycle.extractFees(relevantUpdates)
  }

  def l0Routes(implicit context: L0NodeContext[F]) = masterPlugin.routes.l0Routes
  def dataL1Routes(implicit context: L1NodeContext[F]) = masterPlugin.routes.dataL1Routes
  def currencyL1Routes(implicit context: L1NodeContext[F]) = masterPlugin.routes.currencyL1Routes

  def calculateRewards = masterPlugin.rewards.calculateRewards(state)
  def getState = state.asBase

  // Serialization implementations
  def serializeState(stateToSerialize: DataOnChainState): F[Array[Byte]] =
    (stateToSerialize match {
      case s: POnChain @unchecked => masterPlugin.serializeState(s)
      case _                      => Async[F].raiseError(new RuntimeException("Invalid state type for master plugin"))
    }).asInstanceOf[F[Array[Byte]]]

  def deserializeState(bytes: Array[Byte]) =
    masterPlugin.deserializeState(bytes).map(_.widen[DataOnChainState])

  def serializeUpdate(update: DataUpdate): F[Array[Byte]] =
    (update match {
      case u: U @unchecked => masterPlugin.serializeUpdate(u)
      case _               => Async[F].raiseError(new RuntimeException("Invalid update type for master plugin"))
    }).asInstanceOf[F[Array[Byte]]]

  def deserializeUpdate(bytes: Array[Byte]) =
    masterPlugin.deserializeUpdate(bytes).map(_.widen[DataUpdate])

  def serializeBlock(block: Signed[DataApplicationBlock]) =
    masterPlugin.serializeBlock(block)

  def deserializeBlock(bytes: Array[Byte]) =
    masterPlugin.deserializeBlock(bytes)

  def serializeCalculatedState(stateToSerialize: DataCalculatedState): F[Array[Byte]] =
    (stateToSerialize match {
      case s: PCalculated @unchecked => masterPlugin.serializeCalculatedState(s)
      case _                         => Async[F].raiseError(new RuntimeException("Invalid calculated state type for master plugin"))
    }).asInstanceOf[F[Array[Byte]]]

  def deserializeCalculatedState(bytes: Array[Byte]) =
    masterPlugin.deserializeCalculatedState(bytes).map(_.widen[DataCalculatedState])

  // Encoders/Decoders
  def updateEncoder: Encoder[DataUpdate] = masterPlugin.updateEncoder.contramap[DataUpdate] {
    case u: U @unchecked => u
    case _               => throw new RuntimeException("Invalid update type")
  }

  def updateDecoder: Decoder[DataUpdate] = masterPlugin.updateDecoder.widen[DataUpdate]

  def signedUpdateEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] =
    masterPlugin.signedUpdateEntityDecoder.map(_.widen[DataUpdate])

  def calculatedStateEncoder: Encoder[DataCalculatedState] =
    masterPlugin.calculatedStateEncoder.contramap[DataCalculatedState] {
      case s: PCalculated @unchecked => s
      case _                         => throw new RuntimeException("Invalid calculated state type")
    }

  def calculatedStateDecoder: Decoder[DataCalculatedState] =
    masterPlugin.calculatedStateDecoder.widen[DataCalculatedState]

  def onChainStateEncoder: Encoder[DataOnChainState] =
    masterPlugin.onChainStateEncoder.contramap[DataOnChainState] {
      case s: POnChain @unchecked => s
      case _                      => throw new RuntimeException("Invalid on-chain state type")
    }

  def onChainStateDecoder: Decoder[DataOnChainState] =
    masterPlugin.onChainStateDecoder.widen[DataOnChainState]

  // L0 Lifecycle callbacks
  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]) =
    masterPlugin.onSnapshotConsensusResult(snapshot)

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo)(implicit A: Applicative[F]) =
    masterPlugin.onGlobalSnapshotPull(snapshot, context)

  def getTokenUnlocks(
    stateToProcess: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] =
    masterPlugin.getTokenUnlocks(stateToProcess)

  // Calculated state management
  def getCalculatedState(implicit context: L0NodeContext[F]) =
    masterPlugin.getCalculatedState.map { case (ord, calc) => (ord, calc.asInstanceOf[DataCalculatedState]) }

  def setCalculatedState(ordinal: SnapshotOrdinal, stateToSet: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
    (stateToSet match {
      case s: PCalculated @unchecked => masterPlugin.setCalculatedState(ordinal, s)
      case _                         => Async[F].raiseError(new RuntimeException("Invalid calculated state type"))
    }).asInstanceOf[F[Boolean]]

  def hashCalculatedState(stateToHash: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
    (stateToHash match {
      case s: PCalculated @unchecked => masterPlugin.hashCalculatedState(s)
      case _                         => Async[F].raiseError(new RuntimeException("Invalid calculated state type"))
    }).asInstanceOf[F[Hash]]

  // L1 specific
  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: DataUpdate)(implicit context: L1NodeContext[F], A: Applicative[F]): F[EstimatedFee] =
    (update match {
      case u: U @unchecked => masterPlugin.estimateFee(gsOrdinal)(u)
      case _               => EstimatedFee.empty.pure[F]
    }).asInstanceOf[F[EstimatedFee]]

  def postDataTransactionsRequestDecoder(req: Request[F]) =
    masterPlugin.postDataTransactionsRequestDecoder(req)

  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  ) =
    masterPlugin.postDataTransactionsResponseEncoder(dataRequest, validationResult)
}
