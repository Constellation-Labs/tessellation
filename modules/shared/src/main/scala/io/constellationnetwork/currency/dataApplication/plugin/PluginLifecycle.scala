package io.constellationnetwork.currency.dataApplication.plugin

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.{L0NodeContext, L1NodeContext}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.security.signature.Signed

trait PluginLifecycle[
  F[_],
  PUpdate <: DataUpdate,
  POnChain,
  PCalculated
] {
  def validateUpdate(
    update: PUpdate
  )(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]

  def validateData(
    onChainState: POnChain,
    calculatedState: PCalculated,
    updates: NonEmptyList[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]

  def combine(
    onChainState: POnChain,
    calculatedState: PCalculated,
    updates: List[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F]): F[(POnChain, PCalculated, List[SharedArtifact])]

  def extractFees(
    updates: Seq[Signed[PUpdate]]
  )(implicit context: L0NodeContext[F], F: Async[F]): F[Seq[Signed[FeeTransaction]]] =
    F.pure(Seq.empty)

  def estimateFee(
    update: PUpdate
  )(implicit context: L1NodeContext[F], F: Async[F]): F[EstimatedFee] =
    F.pure(EstimatedFee.empty)
}
