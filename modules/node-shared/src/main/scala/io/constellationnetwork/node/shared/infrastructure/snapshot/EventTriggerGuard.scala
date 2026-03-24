package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.schema.node.NodeState

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Shared guard logic for event-driven consensus triggering.
  *
  * Checks pass when:
  *   1. `triggerEventConsensus` is available (consensus wired)
  *   2. Cluster has >= `minPeers` responsive Ready peers (not solo)
  *   3. Mempool has >= `threshold` pending events (batch efficiency)
  *   4. Cooldown elapsed since last trigger (prevent rapid-fire)
  */
object EventTriggerGuard {

  def apply[F[_]: Async, E, K](
    eventMempool: EventMempool[F, E, K],
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    lastTriggerRef: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F],
    minPeers: Int,
    threshold: Int,
    cooldown: FiniteDuration
  ): F[Unit] =
    triggerEventConsensus match {
      case None => Async[F].unit
      case Some(trigger) =>
        for {
          peers <- clusterStorage.getResponsivePeers.map(_.filter(_.state === NodeState.Ready))
          peerCount = peers.size
          lastFacCount <- getLastFacilitatorCount
          _ <-
            if (peerCount < minPeers)
              Async[F].unit
            else if (lastFacCount > 0 && lastFacCount < minPeers + 1)
              logger.debug(
                s"EventTrigger skipped: last round had $lastFacCount facilitator(s), waiting for multi-node consensus"
              )
            else
              eventMempool.size.flatMap { mempoolSize =>
                if (mempoolSize < threshold)
                  Async[F].unit
                else
                  Clock[F].monotonic.flatMap { now =>
                    val nowMs = now.toMillis
                    lastTriggerRef.modify { lastMs =>
                      val elapsed = nowMs - lastMs
                      if (elapsed >= cooldown.toMillis)
                        (nowMs, true)
                      else
                        (lastMs, false)
                    }.flatMap {
                      case true =>
                        logger.info(
                          s"EventTrigger fired: peers=$peerCount, lastFacilitators=$lastFacCount, pending=$mempoolSize, " +
                            s"threshold=$threshold, cooldown=${cooldown.toSeconds}s"
                        ) >> trigger
                      case false =>
                        Async[F].unit
                    }
                  }
              }
        } yield ()
    }
}
