package io.constellationnetwork.currency.dataApplication.ops

import cats.Applicative
import cats.data.ValidatedNec
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed

import org.http4s.HttpRoutes

trait BaseDataApplicationSharedContextualOps[F[_], Context] {

  def validateFee(gsOrdinal: SnapshotOrdinal)(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
    implicit context: Context,
    A: Applicative[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = ().validNec[DataApplicationValidationError].pure[F]

  def routes(implicit context: Context): HttpRoutes[F]

  def routesPrefix: ExternalUrlPrefix
}
