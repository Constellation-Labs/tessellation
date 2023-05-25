package org.tessellation.sdk.infrastructure.snapshot

import cats.Eval
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshotInfo
import org.tessellation.schema.address.Address
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  type CurrencySnapshotWithState = Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]

  def process(
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
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F]
  ) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)
      def process(
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
          .map { case (_, validatedEvents) => processStateChannelEvents(lastGlobalSnapshotInfo, validatedEvents) }
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
        lastState: CurrencySnapshotInfo,
        lastSnapshot: CurrencyIncrementalSnapshot,
        snapshot: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] = currencySnapshotContextFns.createContext(lastState, lastSnapshot, snapshot)

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
                        applyCurrencySnapshot(lastState, lastIncremental, snapshot).map { state =>
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
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): (SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput]) = {

        val lshToSnapshot: Map[(Address, Hash), StateChannelOutput] = events.map { e =>
          (e.address, e.snapshotBinary.value.lastSnapshotHash) -> e
        }.foldLeft(Map.empty[(Address, Hash), StateChannelOutput]) { (acc, entry) =>
          entry match {
            case (k, newEvent) =>
              acc.updatedWith(k) { maybeEvent =>
                maybeEvent
                  .filter(event => Hash.fromBytes(event.snapshotBinary.content) < Hash.fromBytes(newEvent.snapshotBinary.content))
                  .orElse(newEvent.some)
              }
          }
        }

        val result = events
          .map(_.address)
          .distinct
          .map { address =>
            lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes
              .get(address)
              .map(hash => address -> hash)
              .getOrElse(address -> Hash.empty)
          }
          .mapFilter {
            case (address, initLsh) =>
              def unfold(lsh: Hash): Eval[List[StateChannelOutput]] =
                lshToSnapshot
                  .get((address, lsh))
                  .map { go =>
                    for {
                      head <- Eval.now(go)
                      tail <- go.snapshotBinary.hash.fold(_ => Eval.now(List.empty), unfold)
                    } yield head :: tail
                  }
                  .getOrElse(Eval.now(List.empty))

              unfold(initLsh).value.toNel.map(address -> _.map(_.snapshotBinary).reverse)
          }
          .toSortedMap

        (result, Set.empty[StateChannelOutput])
      }
    }

}
