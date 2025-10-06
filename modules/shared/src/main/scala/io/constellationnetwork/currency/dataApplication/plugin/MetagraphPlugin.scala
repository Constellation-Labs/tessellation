package io.constellationnetwork.currency.dataApplication.plugin

import cats.effect.Async

import scala.reflect.ClassTag

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.plugin.rewards.PluginRewards
import io.constellationnetwork.currency.dataApplication.plugin.routes.PluginRoutes

class UpdateTypeDefinition[U <: DataUpdate](implicit val classTag: ClassTag[U])

object UpdateTypeDefinition {
  def apply[U <: DataUpdate](implicit ct: ClassTag[U]): UpdateTypeDefinition[U] =
    new UpdateTypeDefinition[U]()(ct)
}

trait MetagraphPlugin[
  F[_],
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
] {
  def name: String

  def version: String

  def updateTypes: List[UpdateTypeDefinition[_ <: DataUpdate]]

  def configure(config: PluginConfig): F[Unit]

  def register()(implicit F: Async[F]): F[Unit]

  def handles(update: DataUpdate): Boolean =
    updateTypes.exists(_.classTag.runtimeClass.isInstance(update))

  def lifecycle: PluginLifecycle[F, POnChain, PCalculated]

  def routes: PluginRoutes[F]

  def rewards: PluginRewards[F, POnChain, PCalculated]

  def genesisState: DataState[POnChain, PCalculated]
}

case class PluginConfig(
  enabled: Boolean,
  settings: Map[String, String]
)
