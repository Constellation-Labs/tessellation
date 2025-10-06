package io.constellationnetwork.currency.dataApplication.plugin

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.dataApplication.plugin.rewards.PluginReward
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.circe._
import org.http4s._

// Type-erased wrapper (keep existing)
trait PluginWrapper[F[_]] {
  def name: String
  def handles(update: DataUpdate): Boolean
  def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]
  def validateData(updates: NonEmptyList[Signed[DataUpdate]])(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]
  def combine(updates: List[Signed[DataUpdate]])(implicit context: L0NodeContext[F]): F[Unit]
  def extractFees(updates: Seq[Signed[DataUpdate]])(implicit context: L0NodeContext[F]): F[Seq[Signed[FeeTransaction]]]
  def l0Routes(implicit context: L0NodeContext[F]): HttpRoutes[F]
  def dataL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F]
  def currencyL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F]
  def calculateRewards: F[List[PluginReward]]
  def getState: DataState.Base
}

// Keep existing PluginWrapperImpl
class PluginWrapperImpl[
  F[_]: Async,
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
](
  val plugin: MetagraphPlugin[F, POnChain, PCalculated],
  private var state: DataState[POnChain, PCalculated]
) extends PluginWrapper[F] {

  def name: String = plugin.name
  def handles(update: DataUpdate): Boolean = plugin.handles(update)

  def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    if (handles(update)) {
      plugin.lifecycle.validateUpdate(update)
    } else {
      import cats.data.Validated
      Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
    }

  def validateData(
    updates: NonEmptyList[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    NonEmptyList.fromList(relevantUpdates.toList) match {
      case Some(nel) => plugin.lifecycle.validateData(state, nel)
      case None =>
        import cats.data.Validated
        Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
    }
  }

  def combine(
    updates: List[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[Unit] = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    if (relevantUpdates.isEmpty) {
      Async[F].unit
    } else {
      plugin.lifecycle.combine(state, relevantUpdates).flatMap { newState =>
        Async[F].delay { state = newState }
      }
    }
  }

  def extractFees(updates: Seq[Signed[DataUpdate]])(implicit context: L0NodeContext[F]): F[Seq[Signed[FeeTransaction]]] = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    if (relevantUpdates.isEmpty) Seq.empty[Signed[FeeTransaction]].pure[F]
    else plugin.lifecycle.extractFees(relevantUpdates)
  }

  def l0Routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = plugin.routes.l0Routes
  def dataL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = plugin.routes.dataL1Routes
  def currencyL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = plugin.routes.currencyL1Routes

  def calculateRewards: F[List[PluginReward]] = plugin.rewards.calculateRewards(state)
  def getState: DataState.Base = state.asBase
}

// Updated PluginRegistry with master plugin support
class PluginRegistry[F[_]: Async] private (
  masterPluginRef: Ref[F, Option[MasterPluginWrapper[F]]],
  pluginsRef: Ref[F, List[PluginWrapper[F]]]
) {

  // Register master plugin
  def registerMaster[U <: DataUpdate, POnChain <: DataOnChainState, PCalculated <: DataCalculatedState](
    masterPlugin: MasterPlugin[F, U, POnChain, PCalculated]
  ): F[Unit] =
    for {
      _ <- masterPlugin.register()
      wrapper = new MasterPluginWrapperImpl[F, U, POnChain, PCalculated](masterPlugin, masterPlugin.genesisState)
      _ <- masterPluginRef.set(Some(wrapper))
    } yield ()

  // Register feature plugin
  def register[POnChain <: DataOnChainState, PCalculated <: DataCalculatedState](
    plugin: MetagraphPlugin[F, POnChain, PCalculated]
  ): F[Unit] =
    for {
      _ <- plugin.register()
      wrapper = new PluginWrapperImpl[F, POnChain, PCalculated](plugin, plugin.genesisState)
      _ <- pluginsRef.update(_ :+ wrapper)
    } yield ()

  // Get master plugin
  def getMasterPlugin: F[Option[MasterPluginWrapper[F]]] = masterPluginRef.get

  // ========== Serialization delegates to master plugin ==========

  def serializeState(state: DataOnChainState): F[Array[Byte]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.serializeState(state)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.deserializeState(bytes)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def serializeUpdate(update: DataUpdate): F[Array[Byte]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.serializeUpdate(update)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.deserializeUpdate(bytes)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.serializeBlock(block)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.deserializeBlock(bytes)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.serializeCalculatedState(state)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] =
    getMasterPlugin.flatMap {
      case Some(master) => master.deserializeCalculatedState(bytes)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  // ========== Validation - master + all feature plugins ==========

  def validateUpdate(
    update: DataUpdate
  )(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    for {
      masterValidation <- getMasterPlugin.flatMap {
        case Some(master) => master.validateUpdate(update)
        case None =>
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
      }
      plugins <- pluginsRef.get
      pluginValidations <- plugins.filter(_.handles(update)).traverse(_.validateUpdate(update))
      allValidations = masterValidation :: pluginValidations
      result =
        if (allValidations.isEmpty) {
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](())
        } else {
          allValidations.reduce(_ combine _)
        }
    } yield result

  def validateData(
    updates: NonEmptyList[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    for {
      masterValidation <- getMasterPlugin.flatMap {
        case Some(master) => master.validateData(updates)
        case None =>
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
      }
      plugins <- pluginsRef.get
      pluginValidations <- plugins.traverse(_.validateData(updates))
      allValidations = masterValidation :: pluginValidations
      result =
        if (allValidations.isEmpty) {
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](())
        } else {
          allValidations.reduce(_ combine _)
        }
    } yield result

  // ========== Combine - master + all feature plugins ==========

  def combine(
    updates: List[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[Unit] =
    for {
      _ <- getMasterPlugin.flatMap {
        case Some(master) => master.combine(updates)
        case None         => Async[F].unit
      }
      plugins <- pluginsRef.get
      _ <- plugins.traverse_(_.combine(updates))
    } yield ()

  // ========== Extract fees - master + feature plugins ==========

  def extractAllFees(
    updates: Seq[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F]): F[Seq[Signed[FeeTransaction]]] =
    for {
      masterFees <- getMasterPlugin.flatMap {
        case Some(master) => master.extractFees(updates)
        case None         => Async[F].pure(Seq.empty[Signed[FeeTransaction]])
      }
      plugins <- pluginsRef.get
      pluginFees <- plugins.traverse(_.extractFees(updates)).map(_.flatten)
    } yield masterFees ++ pluginFees

  // ========== Routes - master + feature plugins ==========

  def aggregateL0Routes(implicit context: L0NodeContext[F]): F[HttpRoutes[F]] =
    for {
      masterRoutes <- getMasterPlugin.map(_.map(_.l0Routes).getOrElse(HttpRoutes.empty[F]))
      plugins <- pluginsRef.get
      pluginRoutes = plugins.foldLeft(HttpRoutes.empty[F])(_ <+> _.l0Routes)
    } yield masterRoutes <+> pluginRoutes

  def aggregateDataL1Routes(implicit context: L1NodeContext[F]): F[HttpRoutes[F]] =
    for {
      masterRoutes <- getMasterPlugin.map(_.map(_.dataL1Routes).getOrElse(HttpRoutes.empty[F]))
      plugins <- pluginsRef.get
      pluginRoutes = plugins.foldLeft(HttpRoutes.empty[F])(_ <+> _.dataL1Routes)
    } yield masterRoutes <+> pluginRoutes

  def aggregateCurrencyL1Routes(implicit context: L1NodeContext[F]): F[HttpRoutes[F]] =
    for {
      masterRoutes <- getMasterPlugin.map(_.map(_.currencyL1Routes).getOrElse(HttpRoutes.empty[F]))
      plugins <- pluginsRef.get
      pluginRoutes = plugins.foldLeft(HttpRoutes.empty[F])(_ <+> _.currencyL1Routes)
    } yield masterRoutes <+> pluginRoutes

  // ========== Rewards - all plugins ==========

  def calculateAllRewards: F[List[PluginReward]] =
    for {
      masterRewards <- getMasterPlugin.flatMap {
        case Some(master) => master.calculateRewards
        case None         => Async[F].pure(List.empty[PluginReward])
      }
      plugins <- pluginsRef.get
      pluginRewards <- plugins.flatTraverse(_.calculateRewards)
    } yield masterRewards ++ pluginRewards
}

object PluginRegistry {
  def make[F[_]: Async]: F[PluginRegistry[F]] =
    for {
      masterRef <- Ref.of[F, Option[MasterPluginWrapper[F]]](None)
      pluginsRef <- Ref.of[F, List[PluginWrapper[F]]](List.empty)
    } yield new PluginRegistry[F](masterRef, pluginsRef)
}
