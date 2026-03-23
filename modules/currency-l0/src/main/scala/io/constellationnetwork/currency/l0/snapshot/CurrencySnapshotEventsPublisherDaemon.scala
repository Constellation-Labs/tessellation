package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.{Clock, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor: HasherSelector: SecurityProvider](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    keyPair: KeyPair,
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    eventGossipDaemon: EventGossipDaemon[F, CurrencySnapshotEvent, CurrencyStateKey],
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    consensusConfig: ConsensusConfig
  ): Daemon[F] = {
    val eventTriggerMinPeers = consensusConfig.eventTriggerMinPeers
    val eventTriggerThreshold = consensusConfig.eventTriggerThreshold
    val eventTriggerCooldown = consensusConfig.eventTriggerCooldown
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](CurrencySnapshotEventsPublisherDaemon.getClass)

    val events: Stream[F, CurrencySnapshotEvent] = Stream.fromQueueUnterminated(l1OutputQueue)

    implicit val daEncoder: Encoder[DataTransaction] = DataTransactionCodecs.encoder(maybeDataApplication)

    def signAndAddToMempool(event: CurrencySnapshotEvent)(implicit hasher: Hasher[F]): F[Unit] =
      Signed.forAsyncHasher[F, CurrencySnapshotEvent](event, keyPair).flatMap { signedEvent =>
        signedEvent.toHashed.flatMap { hashedEvent =>
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
              maybeEventTrigger(
                clusterStorage,
                triggerEventConsensus,
                getLastFacilitatorCount,
                eventMempool,
                lastTriggerRef,
                logger,
                eventTriggerMinPeers,
                eventTriggerThreshold,
                eventTriggerCooldown
              )
          }.compile.drain
        }
      }
    }
  }

  /** Trigger event-driven consensus if all guards pass:
    *   1. triggerEventConsensus is available (consensus wired) 2. Cluster has >= eventTriggerMinPeers responsive peers (not solo) 3.
    *      Mempool has >= eventTriggerThreshold pending events (batch efficiency) 4. Cooldown elapsed since last trigger (prevent
    *      rapid-fire)
    */
  private def maybeEventTrigger[F[_]: Async, E, K](
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    eventMempool: EventMempool[F, E, K],
    lastTriggerRef: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F],
    eventTriggerMinPeers: Int,
    eventTriggerThreshold: Int,
    eventTriggerCooldown: FiniteDuration
  ): F[Unit] =
    triggerEventConsensus match {
      case None => Async[F].unit
      case Some(trigger) =>
        for {
          peers <- clusterStorage.getResponsivePeers.map(_.filter(_.state === NodeState.Ready))
          peerCount = peers.size
          lastFacCount <- getLastFacilitatorCount
          _ <-
            if (peerCount < eventTriggerMinPeers)
              Async[F].unit
            else if (lastFacCount > 0 && lastFacCount < eventTriggerMinPeers + 1)
              logger.debug(
                s"EventTrigger skipped: last round had $lastFacCount facilitator(s), waiting for multi-node consensus"
              )
            else
              eventMempool.size.flatMap { mempoolSize =>
                if (mempoolSize < eventTriggerThreshold)
                  Async[F].unit
                else
                  Clock[F].monotonic.flatMap { now =>
                    val nowMs = now.toMillis
                    lastTriggerRef.modify { lastMs =>
                      val elapsed = nowMs - lastMs
                      if (elapsed >= eventTriggerCooldown.toMillis)
                        (nowMs, true)
                      else
                        (lastMs, false)
                    }.flatMap {
                      case true =>
                        logger.info(
                          s"EventTrigger fired: peers=$peerCount, lastFacilitators=$lastFacCount, pending=$mempoolSize, " +
                            s"threshold=$eventTriggerThreshold, cooldown=${eventTriggerCooldown.toSeconds}s"
                        ) >> trigger
                      case false =>
                        Async[F].unit
                    }
                  }
              }
        } yield ()
    }
}
