package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor: HasherSelector: SecurityProvider](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    keyPair: KeyPair,
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    eventGossipDaemon: EventGossipDaemon[F, CurrencySnapshotEvent, CurrencyStateKey],
    triggerPredicate: CurrencySnapshotEvent => Boolean,
    triggerEventConsensus: F[Unit]
  ): Daemon[F] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](CurrencySnapshotEventsPublisherDaemon.getClass)

    val events: Stream[F, CurrencySnapshotEvent] = Stream.fromQueueUnterminated(l1OutputQueue)

    def noopEncoder: Encoder[DataTransaction] = (_: DataTransaction) => Json.Null

    implicit def daEncoder: Encoder[DataTransaction] = maybeDataApplication.map { da =>
      implicit val dataUpdateEncoder: Encoder[DataUpdate] = da.dataEncoder
      DataTransaction.encoder
    }.getOrElse(noopEncoder)

    // Sign events and add to mempool, then publish to gossip network and trigger consensus
    def signAndAddToMempool(event: CurrencySnapshotEvent)(implicit hasher: Hasher[F]): F[Unit] =
      Signed.forAsyncHasher[F, CurrencySnapshotEvent](event, keyPair).flatMap { signedEvent =>
        signedEvent.toHashed.flatMap { hashedEvent =>
          eventMempool.add(signedEvent).flatMap {
            case Right(_) =>
              eventGossipDaemon.publish(hashedEvent) >> triggerEventConsensus.whenA(triggerPredicate(event))
            case Left(reason) =>
              logger.warn(s"Failed to add event to mempool: ${event.getClass.getSimpleName}, reason=$reason")
          }
        }
      }

    Daemon.spawn {
      HasherSelector[F].withCurrent { implicit hasher =>
        events
          .evalMap(signAndAddToMempool)
          .compile
          .drain
      }
    }
  }
}
