package io.constellationnetwork.currency.dataApplication.ops

import cats.data.{Validated, ValidatedNec}
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.reflect.ClassTag

import io.constellationnetwork.currency.dataApplication.Errors.Noop
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.context.L1NodeContext
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed

import org.http4s.HttpRoutes

trait BaseDataApplicationL1ContextualOps[F[_]] extends BaseDataApplicationSharedContextualOps[F, L1NodeContext[F]] {

  def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]): F[ValidatedNec[DataApplicationValidationError, Unit]]

  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: DataUpdate)(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[EstimatedFee] = EstimatedFee.empty.pure[F]
}

object BaseDataApplicationL1ContextualOps {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL1ContextualOps[F, D, DON, DOF]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationL1ContextualOps[F] =
    new BaseDataApplicationL1ContextualOps[F] {
      def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]): F[ValidatedNec[DataApplicationValidationError, Unit]] =
        update match {
          case d: D => service.validateUpdate(d)
          case _    => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L1NodeContext[F],
        A: Applicative[F]
      ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
        service.validateFee(gsOrdinal)(dataUpdate.asInstanceOf[Signed[D]], maybeFeeTransaction)

      override def estimateFee(gsOrdinal: SnapshotOrdinal)(update: DataUpdate)(
        implicit context: L1NodeContext[F],
        A: Applicative[F]
      ): F[EstimatedFee] =
        service.estimateFee(gsOrdinal)(update.asInstanceOf[D])

      def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = service.routes

      def routesPrefix: ExternalUrlPrefix = service.routesPrefix
    }
}
