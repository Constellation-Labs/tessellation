package io.constellationnetwork.currency.dataApplication

import cats.data.NonEmptyList

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.security.signature.Signed

trait PluginSystem {
  def name: String

  def dataUpdateTypes: List[DataUpdate]

  def validateUpdate[F[_]](update: DataUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]

  def validateData[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    state: DataState[DON, DOF],
    updates: NonEmptyList[Signed[D]]
  )(
    implicit context: L0NodeContext[F]
  ): F[DataApplicationValidationErrorOr[Unit]]

  def combine[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    state: DataState[DON, DOF],
    updates: List[Signed[D]]
  )(implicit context: L0NodeContext[F]): F[DataState[DON, DOF]]
}
