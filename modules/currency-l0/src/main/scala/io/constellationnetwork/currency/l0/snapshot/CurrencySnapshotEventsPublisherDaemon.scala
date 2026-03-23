package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.{Clock, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CurrencySnapshotEventsPublisherDaemon {

  /** Minimum cluster peers (excluding self) required to trigger event-driven consensus. */
  val MinClusterPeersForEventTrigger: Int =
    sys.env.getOrElse("CL_EVENT_TRIGGER_MIN_PEERS", "2").toInt

  /** Number of pending mempool events required before triggering event-driven consensus. */
  val EventTriggerThreshold: Int =
    sys.env.getOrElse("CL_EVENT_TRIGGER_THRESHOLD", "1").toInt

  /** Cooldown between event-driven consensus triggers. */
  val EventTriggerCooldown: FiniteDuration =
    sys.env.getOrElse("CL_EVENT_TRIGGER_COOLDOWN_SECONDS", "5").toInt.seconds

  def make[F[_]: Async: Supervisor: HasherSelector: SecurityProvider](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    keyPair: KeyPair,
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    eventGossipDaemon: EventGossipDaemon[F, CurrencySnapshotEvent, CurrencyStateKey],
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int]
  ): Daemon[F] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](CurrencySnapshotEventsPublisherDaemon.getClass)

    val events: Stream[F, CurrencySnapshotEvent] = Stream.fromQueueUnterminated(l1OutputQueue)

    def noopEncoder: Encoder[DataTransaction] = (_: DataTransaction) => Json.Null

    implicit def daEncoder: Encoder[DataTransaction] = maybeDataApplication.map { da =>
      implicit val dataUpdateEncoder: Encoder[DataUpdate] = da.dataEncoder
      DataTransaction.encoder
    }.getOrElse(noopEncoder)

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
              maybeEventTrigger(clusterStorage, triggerEventConsensus, getLastFacilitatorCount, eventMempool, lastTriggerRef, logger)
          }.compile.drain
        }
      }
    }
  }

  /** Trigger event-driven consensus if all guards pass:
    *   1. triggerEventConsensus is available (consensus wired) 2. Cluster has >= MinClusterPeersForEventTrigger responsive peers (not solo)
    *      3. Mempool has >= EventTriggerThreshold pending events (batch efficiency) 4. Cooldown elapsed since last trigger (prevent
    *      rapid-fire)
    */
  private def maybeEventTrigger[F[_]: Async, E, K](
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    eventMempool: EventMempool[F, E, K],
    lastTriggerRef: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F]
  ): F[Unit] =
    triggerEventConsensus match {
      case None => Async[F].unit
      case Some(trigger) =>
        for {
          peers <- clusterStorage.getResponsivePeers.map(_.filter(_.state === NodeState.Ready))
          peerCount = peers.size
          lastFacCount <- getLastFacilitatorCount
          _ <-
            if (peerCount < MinClusterPeersForEventTrigger)
              Async[F].unit
            else if (lastFacCount > 0 && lastFacCount < MinClusterPeersForEventTrigger + 1)
              logger.debug(
                s"EventTrigger skipped: last round had $lastFacCount facilitator(s), waiting for multi-node consensus"
              )
            else
              eventMempool.size.flatMap { mempoolSize =>
                if (mempoolSize < EventTriggerThreshold)
                  Async[F].unit
                else
                  Clock[F].monotonic.flatMap { now =>
                    val nowMs = now.toMillis
                    lastTriggerRef.modify { lastMs =>
                      val elapsed = nowMs - lastMs
                      if (elapsed >= EventTriggerCooldown.toMillis)
                        (nowMs, true)
                      else
                        (lastMs, false)
                    }.flatMap {
                      case true =>
                        logger.info(
                          s"EventTrigger fired: peers=$peerCount, lastFacilitators=$lastFacCount, pending=$mempoolSize, " +
                            s"threshold=$EventTriggerThreshold, cooldown=${EventTriggerCooldown.toSeconds}s"
                        ) >> trigger
                      case false =>
                        Async[F].unit
                    }
                  }
              }
        } yield ()
    }
}
