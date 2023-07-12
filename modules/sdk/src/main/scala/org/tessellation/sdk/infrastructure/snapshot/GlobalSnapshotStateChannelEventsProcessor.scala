package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.json.JsonBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  type CurrencySnapshotWithState = Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]

  def process(
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelOutput]
  ): F[
    (
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      SortedMap[Address, CurrencySnapshotWithState],
      Set[StateChannelOutput]
    )
  ]

  def processCurrencySnapshots(
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
  ): F[SortedMap[Address, NonEmptyList[CurrencySnapshotWithState]]]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def make[F[_]: Async: KryoSerializer](
    stateChannelValidator: StateChannelValidator[F],
    stateChannelManager: GlobalSnapshotStateChannelAcceptanceManager[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F]
  ) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)
      def process(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): F[
        (
          SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
          SortedMap[Address, CurrencySnapshotWithState],
          Set[StateChannelOutput]
        )
      ] =
        events
          .traverse(event => stateChannelValidator.validate(event).map(_.errorMap(error => (event.address, error))))
          .map(_.partitionMap(_.toEither))
          .flatTap {
            case (invalid, _) => logger.warn(s"Invalid state channels events: ${invalid}").whenA(invalid.nonEmpty)
          }
          .flatMap { case (_, validatedEvents) => processStateChannelEvents(snapshotOrdinal, lastGlobalSnapshotInfo, validatedEvents) }
          .flatMap {
            case (scSnapshots, returnedSCEvents) =>
              processCurrencySnapshots(lastGlobalSnapshotInfo, scSnapshots)
                .map(calculateLastCurrencySnapshots(_, lastGlobalSnapshotInfo))
                .map((scSnapshots, _, returnedSCEvents))
          }

      private def calculateLastCurrencySnapshots(
        processedCurrencySnapshots: SortedMap[Address, NonEmptyList[CurrencySnapshotWithState]],
        lastGlobalSnapshotInfo: GlobalSnapshotInfo
      ): SortedMap[Address, CurrencySnapshotWithState] = {
        val lastCurrencySnapshots = processedCurrencySnapshots.map { case (k, v) => k -> v.last }

        lastGlobalSnapshotInfo.lastCurrencySnapshots.concat(lastCurrencySnapshots)
      }

      private def applyCurrencySnapshot(
        currencyAddress: Address,
        lastState: CurrencySnapshotInfo,
        lastSnapshot: CurrencyIncrementalSnapshot,
        snapshot: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] =
        currencySnapshotContextFns
          .createContext(CurrencySnapshotContext(currencyAddress, lastState), lastSnapshot, snapshot)
          .map(_.snapshotInfo)

      def processCurrencySnapshots(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
      ): F[SortedMap[Address, NonEmptyList[CurrencySnapshotWithState]]] =
        events.toList
          .foldLeftM(SortedMap.empty[Address, NonEmptyList[CurrencySnapshotWithState]]) {
            case (agg, (address, binaries)) =>
              type Success = NonEmptyList[CurrencySnapshotWithState]
              type Result = Option[Success]
              type Agg = (Result, List[Signed[StateChannelSnapshotBinary]])

              val initialState = lastGlobalSnapshotInfo.lastCurrencySnapshots.get(address)

              (NonEmptyList.fromList(initialState.toList), binaries.toList.reverse)
                .tailRecM[F, Result] {
                  case (state, Nil) => state.asRight[Agg].pure[F]

                  case (None, head :: tail) =>
                    JsonBinarySerializer
                      .deserialize[Signed[CurrencySnapshot]](head.value.content)
                      .toOption
                      .map { snapshot =>
                        (NonEmptyList.one(snapshot.asLeft).some, tail)
                          .asLeft[Result]
                      }
                      .getOrElse((none[Success], tail).asLeft[Result])
                      .pure[F]

                  case (Some(nel @ NonEmptyList(Left(fullSnapshot), _)), head :: tail) =>
                    JsonBinarySerializer
                      .deserialize[Signed[CurrencyIncrementalSnapshot]](head.value.content)
                      .toOption match {
                      case Some(snapshot) =>
                        (nel.prepend((snapshot, fullSnapshot.value.info).asRight).some, tail).asLeft[Result].pure[F]
                      case None =>
                        (nel.some, tail).asLeft[Result].pure[F]
                    }

                  case (Some(nel @ NonEmptyList(Right((lastIncremental, lastState)), _)), head :: tail) =>
                    JsonBinarySerializer
                      .deserialize[Signed[CurrencyIncrementalSnapshot]](head.value.content)
                      .toOption match {
                      case Some(snapshot) =>
                        applyCurrencySnapshot(address, lastState, lastIncremental, snapshot).map { state =>
                          (nel.prepend((snapshot, state).asRight).some, tail).asLeft[Result]
                        }
                      case None =>
                        (nel.some, tail).asLeft[Result].pure[F]
                    }
                }
                .map(_.map(_.reverse))
                .map { maybeProcessed =>
                  initialState match {
                    case Some(_) => maybeProcessed.flatMap(nel => NonEmptyList.fromList(nel.tail))
                    case None    => maybeProcessed
                  }
                }
                .map {
                  case Some(updates) => agg + (address -> updates)
                  case None          => agg
                }

          }

      private def processStateChannelEvents(
        ordinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput])] =
        stateChannelManager.accept(ordinal, lastGlobalSnapshotInfo, events)

    }

}
