package io.constellationnetwork.currency.dataApplication.services

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.reflect.ClassTag

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.L1NodeContext
import io.constellationnetwork.currency.dataApplication.ops.BaseDataApplicationL1ContextualOps
import io.constellationnetwork.currency.dataApplication.plugin.PluginRegistry
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.circe._
import org.http4s._

trait BaseDataApplicationL1Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationL1ContextualOps[F] {
  def postDataTransactionsRequestDecoder(req: Request[F]): F[DataRequest]
  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  ): F[Response[F]]

  // Plugin support
  def pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] = None
}

object BaseDataApplicationL1Service {
  def apply[F[+_]: Async, D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL1Service[F, D, DON, DOF]
  )(implicit d: ClassTag[D], don: ClassTag[DON], dof: ClassTag[DOF], monadThrow: MonadThrow[F]): BaseDataApplicationL1Service[F] = {

    val base = BaseDataApplicationService.apply[F, D, DON, DOF](service)

    val ctx = BaseDataApplicationL1ContextualOps[F, D, DON, DOF](service)

    new BaseDataApplicationL1Service[F] {

      def serializeState(state: DataOnChainState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] = base.serializeBlock(block)

      def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] = base.deserializeBlock(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder

      def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] = base.signedDataEntityEncoder

      def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] = base.signedDataEntityDecoder

      def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] =
        service.pluginRegistry match {
          case Some(registry) =>
            import cats.data.OptionT

            HttpRoutes[F] { req =>
              for {
                currencyL1PluginRoutes <- OptionT.liftF(registry.aggregateCurrencyL1Routes)
                dataL1PluginRoutes <- OptionT.liftF(registry.aggregateDataL1Routes)
                response <- (currencyL1PluginRoutes <+> dataL1PluginRoutes <+> ctx.routes).run(req)
              } yield response
            }
          case None => ctx.routes
        }

      def validateUpdate(update: DataUpdate)(
        implicit context: L1NodeContext[F]
      ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
        service.pluginRegistry match {
          case Some(registry) =>
            for {
              baseValidation <- ctx.validateUpdate(update)
              pluginValidation <- registry.validateUpdate(update)
            } yield baseValidation.combine(pluginValidation)
          case None =>
            ctx.validateUpdate(update)
        }

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L1NodeContext[F],
        A: Applicative[F]
      ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
        ctx.validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction)

      override def estimateFee(gsOrdinal: SnapshotOrdinal)(
        update: DataUpdate
      )(implicit context: L1NodeContext[F], A: Applicative[F]): F[EstimatedFee] =
        ctx.estimateFee(gsOrdinal)(update)

      override def postDataTransactionsRequestDecoder(req: Request[F]): F[DataRequest] =
        service.postDataTransactionsRequestDecoder(req)

      override def postDataTransactionsResponseEncoder(
        dataRequest: DataRequest,
        validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
      ): F[Response[F]] =
        service.postDataTransactionsResponseEncoder(dataRequest, validationResult)

      def routesPrefix: F[ExternalUrlPrefix] = ctx.routesPrefix

      override def pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] = service.pluginRegistry
    }

  }
}
