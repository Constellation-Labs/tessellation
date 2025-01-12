package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.semigroupal._
import cats.syntax.traverse._
import cats.syntax.unorderedFoldable._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.event.EventCutter
import io.constellationnetwork.schema.{Block, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash._
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.types.numeric.PosInt

object GlobalSnapshotEventCutter {

  def make[F[_]: Async: JsonSerializer](
    maxBinarySizeBytes: PosInt,
    snapshotFeeCalculator: SnapshotBinaryFeeCalculator[F]
  ): EventCutter[F, StateChannelEvent, DAGEvent] =
    new EventCutter[F, StateChannelEvent, DAGEvent] {

      def cut(
        scEvents: List[StateChannelEvent],
        dagEvents: List[DAGEvent],
        info: GlobalSnapshotInfo,
        ordinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[(List[StateChannelEvent], List[DAGEvent])] =
        doesNotExceedMaxSize(scEvents, dagEvents)
          .ifM((scEvents, dagEvents).pure[F], removeEvents(scEvents, dagEvents, info, ordinal))

      def doesNotExceedMaxSize(scEvents: List[StateChannelEvent], dagEvents: List[DAGEvent]): F[Boolean] =
        (JsonSerializer[F].serialize(scEvents), JsonSerializer[F].serialize(dagEvents))
          .mapN((a, b) => a.length + b.length <= maxBinarySizeBytes.value)

      def removeEvents(
        scEvents: List[StateChannelEvent],
        dagEvents: List[DAGEvent],
        info: GlobalSnapshotInfo,
        ordinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[(List[StateChannelEvent], List[DAGEvent])] =
        (
          scEvents.traverse(evt => evt.pure[F].product(StateChannelEventWithFee(evt, info, ordinal))).map(_.toMap),
          dagEvents.traverse(evt => evt.pure[F].product(DAGBlockEventWithFee(evt))).map(_.toMap)
        ).flatMapN {
          case (scEventsMap, dagEventsMap) =>
            def removeOne(scEvents: List[StateChannelEvent], dagEvents: List[DAGEvent]): (List[StateChannelEvent], List[DAGEvent]) =
              (scEvents, dagEvents) match {
                case (Nil, Nil)    => (Nil, Nil)
                case (_ :: t, Nil) => (t, Nil)
                case (Nil, _ :: t) => (Nil, t)
                case (h1 :: t1, h2 :: t2) =>
                  Ordering[EventWithFee].compare(scEventsMap(h1), dagEventsMap(h2)) match {
                    case n if (n == -1) || (n == 0 && t1.length >= t2.length) => (t1, dagEvents)
                    case _                                                    => (scEvents, t2)
                  }
              }

            val scEventsSorted = scEvents.sortWith((left, right) => Ordering[EventWithFee].lt(scEventsMap(left), scEventsMap(right)))
            val dagEventsSorted = dagEvents.sortWith((left, right) => Ordering[EventWithFee].lt(dagEventsMap(left), dagEventsMap(right)))

            (scEventsSorted, dagEventsSorted).tailRecM {
              case (Nil, Nil) =>
                (List.empty[StateChannelEvent], List.empty[DAGEvent])
                  .asRight[(List[StateChannelEvent], List[DAGEvent])]
                  .pure[F]

              case (lefts, rights) =>
                val result = removeOne(lefts, rights)
                doesNotExceedMaxSize(result._1, result._2)
                  .map(Either.cond(_, result, result))
            }
        }

      trait EventWithFee {
        def fee: Long
        def hash: Hash
        def isMyChild(that: EventWithFee): Boolean
      }

      implicit val ordering: Ordering[EventWithFee] =
        Ordering.fromLessThan[EventWithFee] { (left, right) =>
          right.isMyChild(left) ||
          left.fee < right.fee ||
          (left.fee == right.fee && Ordering[Hash].lt(left.hash, right.hash))
        }

      case class StateChannelEventWithFee(event: StateChannelEvent, hash: Hash, fee: Long) extends EventWithFee {
        def isMyChild(that: EventWithFee): Boolean =
          that match {
            case evt: StateChannelEventWithFee => evt.event.value.snapshotBinary.lastSnapshotHash === hash
            case _                             => false
          }
      }
      object StateChannelEventWithFee {
        def apply(event: StateChannelEvent, info: GlobalSnapshotInfo, ordinal: SnapshotOrdinal)(
          implicit hasher: Hasher[F]
        ): F[StateChannelEventWithFee] =
          for {
            hashed <- event.value.snapshotBinary.toHashed
            fee <- snapshotFeeCalculator.calculateFee(event, info, ordinal)
          } yield StateChannelEventWithFee(event, hashed.hash, fee.value)
      }

      case class DAGBlockEventWithFee(event: Hashed[Block]) extends EventWithFee {
        val fee: Long = event.signed.value.transactions.toIterable.map(_.value.fee.value.value).sum

        val hash: Hash = event.hash

        def isMyChild(that: EventWithFee): Boolean =
          that match {
            case evt: DAGBlockEventWithFee => evt.event.signed.value.parent.contains_(event.ownReference)
            case _                         => false
          }

      }
      object DAGBlockEventWithFee {
        def apply(dagEvent: DAGEvent)(implicit hasher: Hasher[F]): F[DAGBlockEventWithFee] =
          dagEvent.value.toHashed[F].map(DAGBlockEventWithFee(_))
      }

    }
}
