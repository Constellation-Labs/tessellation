package io.constellationnetwork.currency.dataApplication.plugin.routes

import cats.effect.Async

import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}

import org.http4s.HttpRoutes

trait PluginRoutes[F[_]] {
  def l0Routes(implicit context: L0NodeContext[F], F: Async[F]): HttpRoutes[F] =
    HttpRoutes.empty[F]
  def dataL1Routes(implicit context: L1NodeContext[F], F: Async[F]): HttpRoutes[F] =
    HttpRoutes.empty[F]
  def currencyL1Routes(implicit context: L1NodeContext[F], F: Async[F]): HttpRoutes[F] =
    HttpRoutes.empty[F]
}
