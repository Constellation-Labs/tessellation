package io.constellationnetwork.currency.dataApplication.plugin.routes

import cats.effect.Async

import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.routes.internal.ExternalUrlPrefix

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes

abstract class PluginRoutes[F[_]: Async] {
  def l0Routes(implicit context: L0NodeContext[F]): HttpRoutes[F] =
    HttpRoutes.empty[F]
  def dataL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F] =
    HttpRoutes.empty[F]
  def currencyL1Routes(implicit context: L1NodeContext[F]): HttpRoutes[F] =
    HttpRoutes.empty[F]

  def routesPrefix: ExternalUrlPrefix = "/data-application"
}
