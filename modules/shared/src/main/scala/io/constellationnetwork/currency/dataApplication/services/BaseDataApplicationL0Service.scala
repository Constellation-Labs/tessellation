package io.constellationnetwork.currency.dataApplication.services

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedSet
import scala.reflect.ClassTag

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.L0NodeContext
import io.constellationnetwork.currency.dataApplication.ops.BaseDataApplicationL0ContextualOps
import io.constellationnetwork.currency.dataApplication.plugin.PluginRegistry
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.circe._
import org.http4s._

trait BaseDataApplicationL0Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationL0ContextualOps[F] {

  def genesis: DataState.Base

  final def serializedOnChainGenesis: F[Array[Byte]] = serializeState(genesis.onChain)

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit]

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Unit]

  override def extractFees(
    ds: Seq[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty[Signed[FeeTransaction]])

  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]]

  def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]]

  def calculatedStateEncoder: Encoder[DataCalculatedState]
  def calculatedStateDecoder: Decoder[DataCalculatedState]

  def pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] = None
}

object BaseDataApplicationL0Service {
  def apply[F[_]: Async, D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL0Service[F, D, DON, DOF]
  )(implicit d: ClassTag[D], don: ClassTag[DON], dof: ClassTag[DOF], monadThrow: MonadThrow[F]): BaseDataApplicationL0Service[F] = {

    val base = BaseDataApplicationService.apply[F, D, DON, DOF](service)

    val ctx = BaseDataApplicationL0ContextualOps[F, D, DON, DOF](service)

    new BaseDataApplicationL0Service[F] {

      def serializeState(state: DataOnChainState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] = base.serializeBlock(block)

      def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] = base.deserializeBlock(bytes)

      def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] = ctx.serializeCalculatedState(state)

      def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] = ctx.deserializeCalculatedState(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder

      def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] = base.signedDataEntityEncoder

      def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] = base.signedDataEntityDecoder

      def genesis: DataState.Base = service.genesis.asBase

      def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] =
        service.pluginRegistry match {
          case Some(registry) =>
            import cats.data.OptionT

            HttpRoutes[F] { req =>
              for {
                pluginRoutes <- OptionT.liftF(registry.aggregateL0Routes)
                response <- (pluginRoutes <+> ctx.routes).run(req)
              } yield response
            }
          case None => ctx.routes
        }

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        service.pluginRegistry match {
          case Some(registry) =>
            for {
              pluginValidation <- registry.validateData(state.onChain, state.calculated, updates)
              baseValidation <- ctx.validateData(state, updates)
            } yield pluginValidation.combine(baseValidation)
          case None =>
            ctx.validateData(state, updates)
        }

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L0NodeContext[F],
        A: Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        ctx.validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction)

      override def extractFees(
        ds: Seq[Signed[DataUpdate]]
      )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        service.pluginRegistry match {
          case Some(registry) =>
            for {
              baseFees <- ctx.extractFees(ds)
              pluginFees <- registry.extractAllFees(ds)
            } yield baseFees ++ pluginFees
          case None =>
            ctx.extractFees(ds)
        }

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataState.Base] =
        service.pluginRegistry match {
          case Some(registry) =>
            for {
              pluginState <- registry.combine(state.onChain, state.calculated, updates)
              finalState <- ctx.combine(DataState(pluginState._1, pluginState._2), updates)
            } yield finalState
          case None =>
            ctx.combine(state, updates)
        }

      def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        ctx.getCalculatedState

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
        ctx.setCalculatedState(ordinal, state)

      def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
        ctx.hashCalculatedState(state)

      override def hashDataUpdate: Option[DataUpdate => F[Hash]] =
        ctx.hashDataUpdate

      def calculatedStateDecoder: Decoder[DataCalculatedState] = service.calculatedStateDecoder.asInstanceOf[Decoder[DataCalculatedState]]

      def calculatedStateEncoder: Encoder[DataCalculatedState] = service.calculatedStateEncoder.asInstanceOf[Encoder[DataCalculatedState]]

      def routesPrefix: F[ExternalUrlPrefix] = ctx.routesPrefix

      def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit] = service.onSnapshotConsensusResult(snapshot)

      def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Unit] =
        service.onGlobalSnapshotPull(snapshot, context)

      def getTokenUnlocks(
        state: DataState[DataOnChainState, DataCalculatedState]
      )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] =
        service.getTokenUnlocks(state)

      override def pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] = service.pluginRegistry
    }
  }
}
