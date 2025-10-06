package io.constellationnetwork.currency.dataApplication.services

import cats.Applicative
import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.L0NodeContext
import io.constellationnetwork.currency.dataApplication.plugin.{MasterPlugin, PluginRegistry}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe._
import org.http4s._

class PluginOnlyDataApplicationL0Service[F[_]: Async](
  registry: PluginRegistry[F],
  genesisState: DataState.Base,
  updateEnc: Encoder[DataUpdate],
  updateDec: Decoder[DataUpdate],
  signedUpdateEntityDec: EntityDecoder[F, Signed[DataUpdate]],
  calcStateEnc: Encoder[DataCalculatedState],
  calcStateDec: Decoder[DataCalculatedState]
) extends BaseDataApplicationL0Service[F] {

  override val pluginRegistry: Option[PluginRegistry[F]] = Some(registry)

  // ========== Serialization - delegates to master plugin ==========

  override def serializeState(state: DataOnChainState): F[Array[Byte]] =
    registry.serializeState(state)

  override def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] =
    registry.deserializeState(bytes)

  override def serializeUpdate(update: DataUpdate): F[Array[Byte]] =
    registry.serializeUpdate(update)

  override def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] =
    registry.deserializeUpdate(bytes)

  override def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] =
    registry.serializeBlock(block)

  override def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] =
    registry.deserializeBlock(bytes)

  override def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] =
    registry.serializeCalculatedState(state)

  override def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] =
    registry.deserializeCalculatedState(bytes)

  // ========== Encoders/Decoders - passed in constructor ==========

  override def dataEncoder: Encoder[DataUpdate] = updateEnc
  override def dataDecoder: Decoder[DataUpdate] = updateDec
  override def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] = signedUpdateEntityDec
  override def calculatedStateEncoder: Encoder[DataCalculatedState] = calcStateEnc
  override def calculatedStateDecoder: Decoder[DataCalculatedState] = calcStateDec

  override def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] = {
    import org.http4s.circe.jsonEncoderOf
    jsonEncoderOf[F, Signed[DataUpdate]](Signed.encoder[DataUpdate](dataEncoder))
  }

  // ========== Genesis ==========

  override def genesis: DataState.Base = genesisState

  // ========== Routes: aggregate all plugins (master + features) ==========

  override def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = {
    import cats.data.OptionT
    HttpRoutes[F] { req =>
      for {
        aggregatedRoutes <- OptionT.liftF(registry.aggregateL0Routes)
        response <- aggregatedRoutes.run(req)
      } yield response
    }
  }

  override def routesPrefix: ExternalUrlPrefix = "/data-application"

  // ========== ValidateData: all plugins (master + features) ==========

  override def validateData(
    state: DataState.Base,
    updates: NonEmptyList[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    registry.validateData(updates)

  override def validateFee(
    gsOrdinal: SnapshotOrdinal
  )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
    implicit context: L0NodeContext[F],
    A: Applicative[F]
  ): F[DataApplicationValidationErrorOr[Unit]] = {
    import cats.data.Validated
    Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
  }

  // ========== ExtractFees: all plugins (master + features) ==========

  override def extractFees(
    ds: Seq[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    registry.extractAllFees(ds)

  // ========== Combine: all plugins (master + features) ==========

  override def combine(
    state: DataState.Base,
    updates: List[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataState.Base] =
    for {
      _ <- registry.combine(updates)
      // Return updated state from master plugin
      masterState <- registry.getMasterPlugin.map(_.map(_.getState).getOrElse(state))
    } yield masterState

  // ========== Calculated State Management ==========

  override def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.getCalculatedState
      case None         => (SnapshotOrdinal.MinValue, genesis.calculated).pure[F]
    }

  override def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.setCalculatedState(ordinal, state)
      case None         => false.pure[F]
    }

  override def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.hashCalculatedState(state)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  // ========== L0 Lifecycle Callbacks ==========

  override def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit] =
    registry.getMasterPlugin.flatMap {
      case Some(master) =>
        implicit val A: Applicative[F] = Async[F]
        master.onSnapshotConsensusResult(snapshot)
      case None => Async[F].unit
    }

  override def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Unit] =
    registry.getMasterPlugin.flatMap {
      case Some(master) =>
        implicit val A: Applicative[F] = Async[F]
        master.onGlobalSnapshotPull(snapshot, context)
      case None => Async[F].unit
    }

  override def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.getTokenUnlocks(state)
      case None         => SortedSet.empty[TokenUnlock].pure[F]
    }
}

object PluginOnlyDataApplicationL0Service {
  def make[F[_]: Async, U <: DataUpdate, POnChain <: DataOnChainState, PCalculated <: DataCalculatedState](
    masterPlugin: MasterPlugin[F, U, POnChain, PCalculated]
  ): F[PluginOnlyDataApplicationL0Service[F]] =
    for {
      registry <- PluginRegistry.make[F]
      _ <- registry.registerMaster(masterPlugin)
    } yield
      new PluginOnlyDataApplicationL0Service[F](
        registry,
        masterPlugin.genesisState.asBase,
        masterPlugin.updateEncoder.contramap[DataUpdate] {
          case u: U @unchecked => u; case _ => throw new RuntimeException("Invalid update")
        },
        masterPlugin.updateDecoder.widen[DataUpdate],
        masterPlugin.signedUpdateEntityDecoder.map(_.widen[DataUpdate]),
        masterPlugin.calculatedStateEncoder.contramap[DataCalculatedState] {
          case s: PCalculated @unchecked => s; case _ => throw new RuntimeException("Invalid state")
        },
        masterPlugin.calculatedStateDecoder.widen[DataCalculatedState]
      )
}
