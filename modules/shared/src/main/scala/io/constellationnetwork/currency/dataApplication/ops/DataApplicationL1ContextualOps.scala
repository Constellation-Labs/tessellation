package io.constellationnetwork.currency.dataApplication.ops

import cats.Applicative
import cats.data.ValidatedNec
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.L1NodeContext
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.schema.SnapshotOrdinal

trait DataApplicationL1ContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationSharedContextualOps[F, D, DON, DOF, L1NodeContext[F]] {

  def validateUpdate(update: D)(implicit context: L1NodeContext[F]): F[ValidatedNec[DataApplicationValidationError, Unit]]

  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: D)(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[EstimatedFee] = EstimatedFee.empty.pure[F]
}
