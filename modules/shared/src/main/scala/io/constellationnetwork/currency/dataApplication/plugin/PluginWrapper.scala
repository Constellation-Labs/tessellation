package io.constellationnetwork.currency.dataApplication.plugin

import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.dataApplication.plugin.rewards.PluginReward
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.security.signature.Signed

import org.http4s._

// Type-erased wrapper
trait PluginWrapper[
  F[_],
  PUpdate <: DataUpdate,
  POnChain,
  PCalculated
] {
  def name: String

  def handles(update: PUpdate): Boolean

  def validateUpdate(update: PUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]

  def validateData(onChainState: POnChain, calculatedState: PCalculated, updates: NonEmptyList[Signed[PUpdate]])(
    implicit context: L0NodeContext[F]
  ): F[DataApplicationValidationErrorOr[Unit]]

  def combine(onChainState: POnChain, calculatedState: PCalculated, updates: List[Signed[PUpdate]])(
    implicit context: L0NodeContext[F]
  ): F[(POnChain, PCalculated, List[SharedArtifact])]

  def extractFees(updates: Seq[Signed[PUpdate]])(implicit context: L0NodeContext[F]): F[Seq[Signed[FeeTransaction]]]

  def l0Routes(implicit context: L0NodeContext[F]): HttpRoutes[F]

  def dataL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F]

  def currencyL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F]

  def calculateRewards(onChainState: POnChain, calculatedState: PCalculated): F[List[PluginReward]]
}

class PluginWrapperImpl[
  F[_]: Async,
  PUpdate <: DataUpdate,
  POnChain,
  PCalculated
](
  val plugin: MetagraphPlugin[F, PUpdate, POnChain, PCalculated]
) extends PluginWrapper[F, PUpdate, POnChain, PCalculated] {

  def name: String = plugin.name

  def handles(update: PUpdate): Boolean = plugin.handles(update)

  def validateUpdate(update: PUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    if (handles(update)) {
      plugin.lifecycle.validateUpdate(update)
    } else {
      import cats.data.Validated
      Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
    }

  def validateData(
    onChainState: POnChain,
    calculatedState: PCalculated,
    updates: NonEmptyList[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    NonEmptyList.fromList(relevantUpdates) match {
      case Some(nel) =>
        plugin.lifecycle.validateData(onChainState, calculatedState, nel)
      case None =>
        import cats.data.Validated
        Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
    }
  }

  def combine(
    onChainState: POnChain,
    calculatedState: PCalculated,
    updates: List[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F]): F[(POnChain, PCalculated, List[SharedArtifact])] = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    if (relevantUpdates.isEmpty) {
      (onChainState, calculatedState, List.empty[SharedArtifact]).pure
    } else {
      plugin.lifecycle.combine(onChainState, calculatedState, relevantUpdates)
    }
  }

  def extractFees(updates: Seq[Signed[PUpdate]])(implicit context: L0NodeContext[F]): F[Seq[Signed[FeeTransaction]]] = {
    val relevantUpdates = updates.filter(u => handles(u.value))
    if (relevantUpdates.isEmpty) Seq.empty[Signed[FeeTransaction]].pure[F]
    else plugin.lifecycle.extractFees(relevantUpdates)
  }

  def l0Routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = plugin.routes.l0Routes

  def dataL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = plugin.routes.dataL1Routes

  def currencyL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = plugin.routes.currencyL1Routes

  def calculateRewards(onChainState: POnChain, calculatedState: PCalculated): F[List[PluginReward]] =
    plugin.rewards.calculateRewards(onChainState, calculatedState)
}

// Updated PluginRegistry with master plugin support
class PluginRegistry[
  F[_]: Async,
  PUpdate <: DataUpdate,
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
] private (
  masterPluginRef: Ref[F, Option[MasterPluginWrapper[F, PUpdate, POnChain, PCalculated]]],
  pluginsRef: Ref[F, List[PluginWrapper[F, PUpdate, POnChain, PCalculated]]]
) {

  def registerMaster(
    masterPlugin: MasterPlugin[F, PUpdate, POnChain, PCalculated]
  ): F[Unit] =
    for {
      _ <- masterPlugin.register()
      wrapper = new MasterPluginWrapperImpl[F, PUpdate, POnChain, PCalculated](masterPlugin)
      _ <- masterPluginRef.set(wrapper.some)
    } yield ()

  def register[U <: PUpdate, O <: POnChain, C <: PCalculated](
    plugin: MetagraphPlugin[F, U, O, C]
  ): F[Unit] =
    for {
      _ <- plugin.register()
      wrapper = new PluginWrapperImpl[F, U, O, C](plugin)
      _ <- pluginsRef.update(_ :+ wrapper.asInstanceOf[PluginWrapper[F, PUpdate, POnChain, PCalculated]])
    } yield ()

  def getMasterPlugin: F[Option[MasterPluginWrapper[F, PUpdate, POnChain, PCalculated]]] = masterPluginRef.get

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

  def routesPrefix: F[ExternalUrlPrefix] =
    getMasterPlugin.flatMap {
      case Some(master) => master.routesPrefix.pure
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  def validateUpdate(
    update: PUpdate
  )(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    for {
      plugins <- pluginsRef.get

      pluginValidations <- plugins.filter(_.handles(update)).traverse(_.validateUpdate(update))
      masterValidation <- getMasterPlugin.flatMap {
        case Some(master) => master.validateUpdate(update)
        case None =>
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
      }
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
    onChainState: POnChain,
    calculatedState: PCalculated,
    updates: NonEmptyList[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
    for {
      plugins <- pluginsRef.get
      pluginValidations <- plugins.traverse(_.validateData(onChainState, calculatedState, updates))
      masterValidation <- getMasterPlugin.flatMap {
        case Some(master) => master.validateData(onChainState, calculatedState, updates)
        case None =>
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
      }
      allValidations = masterValidation :: pluginValidations
      result =
        if (allValidations.isEmpty) {
          import cats.data.Validated
          Validated.validNec[DataApplicationValidationError, Unit](())
        } else {
          allValidations.reduce(_ combine _)
        }
    } yield result

  def combine(
    onChainState: POnChain,
    calculatedState: PCalculated,
    updates: List[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F]): F[(POnChain, PCalculated, List[SharedArtifact])] =
    for {
      plugins <- pluginsRef.get

      stateAfterPlugins <- plugins.foldLeftM((onChainState, calculatedState, List.empty[SharedArtifact])) {
        case ((currentOnChain, currentCalculated, artifacts), plugin) =>
          plugin.combine(currentOnChain, currentCalculated, updates).map {
            case (newOnChain, newCalculated, newArtifacts) =>
              (newOnChain, newCalculated, artifacts ++ newArtifacts)
          }
      }

      finalState <- getMasterPlugin.flatMap {
        case Some(master) =>
          val (onChain, calculated, artifacts) = stateAfterPlugins
          master.combine(onChain, calculated, updates).map {
            case (newOnChain, newCalculated, newArtifacts) =>
              (newOnChain, newCalculated, artifacts ++ newArtifacts)
          }
        case None => stateAfterPlugins.pure[F]
      }
    } yield finalState

  // ========== Extract fees - master + feature plugins ==========

  def extractAllFees(
    updates: Seq[Signed[PUpdate]]
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

  def calculateAllRewards(
    onChainState: POnChain,
    calculatedState: PCalculated
  ): F[List[PluginReward]] =
    for {
      masterRewards <- getMasterPlugin.flatMap {
        case Some(master) => master.calculateRewards(onChainState, calculatedState)
        case None         => Async[F].pure(List.empty[PluginReward])
      }
      plugins <- pluginsRef.get
      pluginRewards <- plugins.flatTraverse(_.calculateRewards(onChainState, calculatedState))
    } yield masterRewards ++ pluginRewards
}

object PluginRegistry {
  def make[
    F[_]: Async,
    PUpdate <: DataUpdate,
    POnChain <: DataOnChainState,
    PCalculated <: DataCalculatedState
  ]: F[PluginRegistry[F, PUpdate, POnChain, PCalculated]] =
    for {
      masterRef <- Ref.of[F, Option[MasterPluginWrapper[F, PUpdate, POnChain, PCalculated]]](None)
      pluginsRef <- Ref.of[F, List[PluginWrapper[F, PUpdate, POnChain, PCalculated]]](List.empty)
    } yield new PluginRegistry[F, PUpdate, POnChain, PCalculated](masterRef, pluginsRef)
}
