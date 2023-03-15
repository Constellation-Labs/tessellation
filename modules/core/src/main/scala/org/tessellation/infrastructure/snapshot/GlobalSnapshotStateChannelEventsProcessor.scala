package org.tessellation.infrastructure.snapshot

import cats.Eval
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshotInfo
import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  def process(
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelEvent]
  ): F[
    (
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
      Set[StateChannelEvent]
    )
  ]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def make[F[_]: Async: KryoSerializer](stateChannelValidator: StateChannelValidator[F]) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)
      def process(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelEvent]
      ): F[
        (
          SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
          SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
          Set[StateChannelEvent]
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
              processCurrencySnapshots(lastGlobalSnapshotInfo, scSnapshots).map((scSnapshots, _, returnedSCEvents))
          }

      private def applyCurrencySnapshot(
        lastState: CurrencySnapshotInfo,
        lastSnapshot: CurrencyIncrementalSnapshot,
        snapshot: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] = ???

      private def processCurrencySnapshots(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
      ): F[SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)]] =
        events
          .foldLeft(SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)].pure[F]) {
            case (aggF, (address, binaries)) =>
              aggF.flatMap { agg =>
                type SuccessT = (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)
                type LeftT =
                  (Option[(Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)], List[Signed[StateChannelSnapshotBinary]])
                type RightT = Option[SuccessT]

                (lastGlobalSnapshotInfo.lastCurrencySnapshots.get(address), binaries.toList)
                  .tailRecM[F, RightT] {
                    case (state, Nil) => state.asRight[LeftT].pure[F]

                    case (None, head :: tail) =>
                      KryoSerializer[F]
                        .deserialize[Signed[CurrencySnapshot]](head.value.content)
                        .toOption
                        .map { snapshot =>
                          ((none[Signed[CurrencyIncrementalSnapshot]], snapshot.info).some, tail).asLeft[RightT]
                        }
                        .getOrElse((none[SuccessT], tail).asLeft[RightT])
                        .pure[F]

                    case (Some((maybeLastSnapshot, lastState)), head :: tail) =>
                      KryoSerializer[F]
                        .deserialize[Signed[CurrencyIncrementalSnapshot]](head.value.content)
                        .toOption
                        .map { snapshot =>
                          maybeLastSnapshot
                            .map(applyCurrencySnapshot(lastState, _, snapshot))
                            .getOrElse(lastState.pure[F])
                            .map { state =>
                              ((snapshot.some, state).some, tail).asLeft[RightT]
                            }
                        }
                        .getOrElse(((maybeLastSnapshot, lastState).some, tail).asLeft[RightT].pure[F])
                  }
                  .map {
                    _.map(updated => agg + (address -> updated)).getOrElse(agg)
                  }

              }
          }

      private def processStateChannelEvents(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelEvent]
      ): (SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelEvent]) = {

        val lshToSnapshot: Map[(Address, Hash), StateChannelEvent] = events.map { e =>
          (e.address, e.snapshotBinary.value.lastSnapshotHash) -> e
        }.foldLeft(Map.empty[(Address, Hash), StateChannelEvent]) { (acc, entry) =>
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
              def unfold(lsh: Hash): Eval[List[StateChannelEvent]] =
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

        (result, Set.empty[StateChannelEvent])
      }
    }

}
