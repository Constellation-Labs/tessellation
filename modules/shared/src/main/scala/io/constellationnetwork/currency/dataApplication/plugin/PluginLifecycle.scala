package io.constellationnetwork.currency.dataApplication.plugin

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.security.signature.Signed

trait PluginLifecycle[
  F[_],
  POnChain <: DataOnChainState,
  PCalculated <: DataCalculatedState
] {

  // L1 Validation - validates individual update
  // Plugin receives DataUpdate and pattern matches on the types it cares about
  def validateUpdate(
    update: DataUpdate
  )(implicit context: L1NodeContext[F], F: Async[F]): F[DataApplicationValidationErrorOr[Unit]]

  // L0 Validation - validates updates against state
  def validateData(
    state: DataState[POnChain, PCalculated],
    updates: NonEmptyList[Signed[DataUpdate]] // Receives all updates, filters internally
  )(implicit context: L0NodeContext[F], F: Async[F]): F[DataApplicationValidationErrorOr[Unit]]

  // L0 State combination - apply updates to plugin state
  def combine(
    state: DataState[POnChain, PCalculated],
    updates: List[Signed[DataUpdate]] // Receives all updates, filters internally
  )(implicit context: L0NodeContext[F], F: Async[F]): F[DataState[POnChain, PCalculated]]

  // Extract fees from plugin updates
  def extractFees(
    updates: Seq[Signed[DataUpdate]]
  )(implicit context: L0NodeContext[F], F: Async[F]): F[Seq[Signed[FeeTransaction]]] =
    F.pure(Seq.empty)

  // Estimate fees for an update (L1)
  def estimateFee(
    update: DataUpdate
  )(implicit context: L1NodeContext[F], F: Async[F]): F[EstimatedFee] =
    F.pure(EstimatedFee.empty)
}
