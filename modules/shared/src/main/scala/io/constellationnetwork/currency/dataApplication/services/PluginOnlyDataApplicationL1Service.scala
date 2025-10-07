package io.constellationnetwork.currency.dataApplication.services

import cats.Applicative
import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.block.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.context.L1NodeContext
import io.constellationnetwork.currency.dataApplication.plugin.PluginRegistry
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe._
import org.http4s._

class PluginOnlyDataApplicationL1Service[F[_]: Async](
  registry: PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState],
  updateEnc: Encoder[DataUpdate],
  updateDec: Decoder[DataUpdate],
  signedUpdateEntityDec: EntityDecoder[F, Signed[DataUpdate]]
) extends BaseDataApplicationL1Service[F] {

  override val pluginRegistry: Option[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]] =
    Some(registry)

  // ========== Serialization - delegates to master plugin ==========

  override def serializeState(state: DataOnChainState): F[Array[Byte]] =
    registry.serializeState(state)

  override def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] =
    registry.deserializeState(bytes)

  override def serializeUpdate(update: DataUpdate): F[Array[Byte]] =
    registry.serializeUpdate(update)

  override def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] =
    registry.deserializeUpdate(bytes)

  override def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] =
    registry.serializeBlock(block)

  override def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] =
    registry.deserializeBlock(bytes)

  // ========== Encoders/Decoders - passed in constructor ==========

  override def dataEncoder: Encoder[DataUpdate] = updateEnc
  override def dataDecoder: Decoder[DataUpdate] = updateDec
  override def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] = signedUpdateEntityDec

  override def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] = {
    import org.http4s.circe.jsonEncoderOf
    jsonEncoderOf[F, Signed[DataUpdate]](Signed.encoder[DataUpdate](dataEncoder))
  }

  // ========== Routes: aggregate all plugins (master + features) ==========

  override def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = {
    import cats.data.OptionT
    HttpRoutes[F] { req =>
      for {
        aggregatedRoutes <- OptionT.liftF(registry.aggregateDataL1Routes)
        response <- aggregatedRoutes.run(req)
      } yield response
    }
  }

  override def routesPrefix: F[ExternalUrlPrefix] = registry.routesPrefix

  // ========== ValidateUpdate: all plugins (master + features) ==========

  override def validateUpdate(update: DataUpdate)(
    implicit context: L1NodeContext[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    registry.validateUpdate(update)

  override def validateFee(
    gsOrdinal: SnapshotOrdinal
  )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {
    import cats.data.Validated
    Validated.validNec[DataApplicationValidationError, Unit](()).pure[F]
  }

  // ========== Fee Estimation ==========

  override def estimateFee(gsOrdinal: SnapshotOrdinal)(
    update: DataUpdate
  )(implicit context: L1NodeContext[F], A: Applicative[F]): F[EstimatedFee] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.estimateFee(gsOrdinal)(update)
      case None         => EstimatedFee.empty.pure[F]
    }

  // ========== Request/Response Handling ==========

  override def postDataTransactionsRequestDecoder(req: Request[F]): F[DataRequest] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.postDataTransactionsRequestDecoder(req)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }

  override def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  ): F[Response[F]] =
    registry.getMasterPlugin.flatMap {
      case Some(master) => master.postDataTransactionsResponseEncoder(dataRequest, validationResult)
      case None         => Async[F].raiseError(new RuntimeException("No master plugin registered"))
    }
}

object PluginOnlyDataApplicationL1Service {
  def make[F[_]: Async, U <: DataUpdate, POnChain <: DataOnChainState, PCalculated <: DataCalculatedState](
    masterPlugin: io.constellationnetwork.currency.dataApplication.plugin.MasterPlugin[F, U, POnChain, PCalculated]
  ): F[PluginOnlyDataApplicationL1Service[F]] =
    for {
      registry <- PluginRegistry.make[F, U, POnChain, PCalculated]
      _ <- registry.registerMaster(masterPlugin)
    } yield
      new PluginOnlyDataApplicationL1Service[F](
        registry.asInstanceOf[PluginRegistry[F, DataUpdate, DataOnChainState, DataCalculatedState]],
        masterPlugin.updateEncoder.contramap[DataUpdate] {
          case u: U @unchecked => u
          case _               => throw new RuntimeException("Invalid update type")
        },
        masterPlugin.updateDecoder.widen[DataUpdate],
        masterPlugin.signedUpdateEntityDecoder.map(_.widen[DataUpdate])
      )
}
