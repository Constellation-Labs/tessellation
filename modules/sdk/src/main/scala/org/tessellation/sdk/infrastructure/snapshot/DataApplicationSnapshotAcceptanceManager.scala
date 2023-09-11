package org.tessellation.sdk.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication._
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency.DataApplicationPart
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationSnapshotAcceptanceManager[F[_]] {
  def accept(
    maybeLastDataApplication: Option[Array[Byte]],
    dataBlocks: List[Signed[DataApplicationBlock]],
    lastOrdinal: SnapshotOrdinal,
    currentOrdinal: SnapshotOrdinal
  ): F[Option[DataApplicationAcceptanceResult]]
}

case class DataApplicationAcceptanceResult(
  dataApplicationPart: DataApplicationPart,
  calculatedState: DataCalculatedState
)

object DataApplicationSnapshotAcceptanceManager {

  case class CalculatedStateDoesNotMatchOrdinal(calculatedStateOrdinal: SnapshotOrdinal, expectedOrdinal: SnapshotOrdinal)
      extends NoStackTrace {
    override def getMessage: String =
      s"Calculated state ordinal=${calculatedStateOrdinal.show} does not match expected ordinal=${expectedOrdinal.show}"
  }

  def make[F[_]: Async](
    service: BaseDataApplicationL0Service[F],
    nodeContext: L0NodeContext[F]
  ): DataApplicationSnapshotAcceptanceManager[F] = new DataApplicationSnapshotAcceptanceManager[F] {
    private val logger = Slf4jLogger.getLogger

    def expectCalculatedStateOrdinal(
      expectedOrdinal: SnapshotOrdinal
    )(calculatedState: (SnapshotOrdinal, DataCalculatedState)): F[DataCalculatedState] =
      calculatedState match {
        case (ordinal, state) =>
          CalculatedStateDoesNotMatchOrdinal(ordinal, expectedOrdinal)
            .raiseError[F, Unit]
            .unlessA(ordinal === expectedOrdinal)
            .as(state)
      }

    def accept(
      maybeLastDataApplication: Option[Array[Byte]],
      dataBlocks: List[Signed[DataApplicationBlock]],
      lastOrdinal: SnapshotOrdinal,
      currentOrdinal: SnapshotOrdinal
    ): F[Option[DataApplicationAcceptanceResult]] = {
      implicit val context: L0NodeContext[F] = nodeContext

      for {
        maybeLastDataOnChainState <- maybeLastDataApplication.flatTraverse { lastDataApplication =>
          service
            .deserializeState(lastDataApplication)
            .flatTap {
              case Left(err) => logger.warn(err)("Cannot deserialize custom state")
              case Right(_)  => Applicative[F].unit
            }
            .map(_.toOption)
            .handleErrorWith(err =>
              logger.error(err)(s"Unhandled exception during deserialization data application, fallback to empty state").as(none)
            )
        }

        maybeNewDataState <- maybeLastDataOnChainState.flatTraverse { lastState =>
          NonEmptyList
            .fromList(dataBlocks.distinctBy(_.value.roundId))
            .map(_.flatMap(_.value.updates))
            .map { updates =>
              service.getCalculatedState
                .flatMap(expectCalculatedStateOrdinal(lastOrdinal))
                .flatMap { calculatedState =>
                  service.validateData(DataState(lastState, calculatedState), updates)
                }
                .flatTap { validated =>
                  logger.warn(s"Data application is invalid, errors: ${validated.toString}").whenA(validated.isInvalid)
                }
                .map(_.isValid)
                .handleErrorWith(err =>
                  logger.error(err)("Unhandled exception during validating data application, assumed as invalid").as(false)
                )
                .ifF(updates.toList, List.empty[Signed[DataUpdate]])
            }
            .getOrElse(List.empty[Signed[DataUpdate]].pure[F])
            .flatMap { updates =>
              service.getCalculatedState
                .flatMap(expectCalculatedStateOrdinal(lastOrdinal))
                .map(DataState(lastState, _))
                .flatMap(service.combine(_, updates))
                .flatTap(state => service.setCalculatedState(currentOrdinal, state.calculated))
                .flatMap(state => service.serializeState(state.onChain))
            }
            .map(_.some)
            .handleErrorWith(err =>
              logger
                .error(err)(
                  "Unhandled exception during combine and serialize data application, fallback to last data application"
                )
                .as(maybeLastDataApplication)
            )
        }

        calculatedState <- service.getCalculatedState
          .flatMap(expectCalculatedStateOrdinal(currentOrdinal))

        serializedDataBlocks <- dataBlocks.traverse(service.serializeBlock)

        calculatedStateHash <- service.hashCalculatedState(calculatedState)

        result = maybeNewDataState.map { state =>
          DataApplicationAcceptanceResult(
            DataApplicationPart(state, serializedDataBlocks, calculatedStateHash),
            calculatedState
          )

        }
      } yield result
    }
  }
}
