package io.constellationnetwork.currency.dataApplication.ops

import cats.Applicative
import cats.data.ValidatedNec
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes

trait DataApplicationSharedContextualOps[F[
  _
], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState, Context] {

  def validateFee(gsOrdinal: SnapshotOrdinal)(dataUpdate: Signed[D], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
    implicit context: Context,
    A: Applicative[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = ().validNec[DataApplicationValidationError].pure[F]

  def routes(implicit context: Context): HttpRoutes[F]

  def routesPrefix: ExternalUrlPrefix = "/data-application"
}
