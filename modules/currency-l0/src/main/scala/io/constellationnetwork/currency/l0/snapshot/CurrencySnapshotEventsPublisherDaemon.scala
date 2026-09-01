package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{EventGossipBounds, EventGossipDaemon}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.EventTriggerGuard
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor: HasherSelector: SecurityProvider: Metrics](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    keyPair: KeyPair,
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    eventGossipDaemon: EventGossipDaemon[F, CurrencySnapshotEvent, CurrencyStateKey],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    consensusConfig: ConsensusConfig
  ): Daemon[F] = {
    val eventTriggerThreshold = consensusConfig.eventTriggerThreshold
    val eventTriggerCooldown = consensusConfig.eventTriggerCooldown
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](CurrencySnapshotEventsPublisherDaemon.getClass)

    val events: Stream[F, CurrencySnapshotEvent] = Stream.fromQueueUnterminated(l1OutputQueue)

    implicit val daEncoder: Encoder[DataTransaction] = DataTransactionCodecs.encoder(maybeDataApplication)

    def signAndAddToMempool(event: CurrencySnapshotEvent)(implicit hasher: Hasher[F]): F[Unit] =
      Signed.forAsyncHasher[F, CurrencySnapshotEvent](event, keyPair).flatMap { signedEvent =>
        signedEvent.toHashed.flatMap { hashedEvent =>
          if (!EventGossipBounds.isPullable(hashedEvent.hash, signedEvent))
            Metrics[F].incrementCounter(
              "dag_currency_l0_event_gossip_rejected_total",
              Seq(Metrics.unsafeLabelName("reason") -> "iwant_response_too_large")
            ) >> logger.error(
              s"Rejected Currency event that cannot fit one bounded IWANT response: " +
                s"event=${event.getClass.getSimpleName} maxBytes=${EventGossipBounds.MaxIWantResponseBytes}"
            )
          else
            eventMempool.add(signedEvent).flatMap {
              case Right(_) =>
                eventGossipDaemon.publish(hashedEvent)
              case Left(reason) =>
                logger.warn(s"Failed to add event to mempool: ${event.getClass.getSimpleName}, reason=$reason")
            }
        }
      }

    Daemon.spawn {
      Ref.of[F, Long](0L).flatMap { lastTriggerRef =>
        HasherSelector[F].withCurrent { implicit hasher =>
          events.evalMap { event =>
            signAndAddToMempool(event) >>
              EventTriggerGuard(
                eventMempool,
                triggerEventConsensus,
                getLastFacilitatorCount,
                lastTriggerRef,
                logger,
                eventTriggerThreshold,
                eventTriggerCooldown
              )
          }.compile.drain
        }
      }
    }
  }

}
