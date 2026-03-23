package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.{Clock, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.dag.l0.domain.delegatedStake.{CreateDelegatedStakeOutput, DelegatedStakeOutput, WithdrawDelegatedStakeOutput}
import io.constellationnetwork.dag.l0.domain.nodeCollateral.{CreateNodeCollateralOutput, NodeCollateralOutput, WithdrawNodeCollateralOutput}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.{NodeState, UpdateNodeParameters}
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

import fs2.Stream
import io.circe.{Encoder => CirceEncoder}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalSnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor: HasherSelector: SecurityProvider](
    stateChannelOutputs: Queue[F, StateChannelOutput],
    l1OutputQueue: Queue[F, Signed[Block]],
    allowSpendOutputQueue: Queue[F, Signed[AllowSpendBlock]],
    tokenLockOutputQueue: Queue[F, Signed[TokenLockBlock]],
    updateNodeParametersQueue: Queue[F, Signed[UpdateNodeParameters]],
    delegatedStakeOutputQueue: Queue[F, DelegatedStakeOutput],
    nodeCollateralOutputQueue: Queue[F, NodeCollateralOutput],
    keyPair: KeyPair,
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    eventGossipDaemon: EventGossipDaemon[F, GlobalSnapshotEvent, GlobalStateKey],
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    consensusConfig: ConsensusConfig
  ): Daemon[F] = {
    val eventTriggerMinPeers = consensusConfig.eventTriggerMinPeers
    val eventTriggerThreshold = consensusConfig.eventTriggerThreshold
    val eventTriggerCooldown = consensusConfig.eventTriggerCooldown
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotEventsPublisherDaemon.getClass)

    val events: Stream[F, GlobalSnapshotEvent] = Stream
      .fromQueueUnterminated(stateChannelOutputs)
      .map(StateChannelEvent(_))
      .merge(
        Stream
          .fromQueueUnterminated(l1OutputQueue)
          .map(DAGEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(allowSpendOutputQueue)
          .map(AllowSpendEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(tokenLockOutputQueue)
          .map(TokenLockEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(updateNodeParametersQueue)
          .map(UpdateNodeParametersEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(delegatedStakeOutputQueue)
          .map {
            case CreateDelegatedStakeOutput(data)   => CreateDelegatedStakeEvent(data)
            case WithdrawDelegatedStakeOutput(data) => WithdrawDelegatedStakeEvent(data)
          }
      )
      .merge(
        Stream
          .fromQueueUnterminated(nodeCollateralOutputQueue)
          .map {
            case CreateNodeCollateralOutput(data)   => CreateNodeCollateralEvent(data)
            case WithdrawNodeCollateralOutput(data) => WithdrawNodeCollateralEvent(data)
          }
      )

    Daemon.spawn {
      Ref.of[F, Long](0L).flatMap { lastTriggerRef =>
        HasherSelector[F].withCurrent { implicit hasher =>
          events.evalMap { event =>
            signAndPublish(event, keyPair, eventMempool, eventGossipDaemon, logger) >>
              maybeEventTrigger(
                eventMempool,
                clusterStorage,
                triggerEventConsensus,
                getLastFacilitatorCount,
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

  private def signAndPublish[F[_]: Async: SecurityProvider, E, K](
    event: E,
    keyPair: KeyPair,
    eventMempool: EventMempool[F, E, K],
    eventGossipDaemon: EventGossipDaemon[F, E, K],
    logger: SelfAwareStructuredLogger[F]
  )(implicit hasher: Hasher[F], signed: CirceEncoder[E]): F[Unit] =
    Signed.forAsyncHasher[F, E](event, keyPair).flatMap { signedEvent =>
      signedEvent.toHashed.flatMap { hashedEvent =>
        eventMempool.add(signedEvent).flatMap {
          case Right(_) =>
            eventGossipDaemon.publish(hashedEvent)
          case Left(reason) =>
            logger.warn(s"Failed to add event to mempool: ${event.getClass.getSimpleName}, reason=$reason")
        }
      }
    }

  /** Trigger event-driven consensus if all guards pass:
    *   1. triggerEventConsensus is available (consensus wired) 2. Cluster has >= eventTriggerMinPeers responsive peers (not solo) 3.
    *      Mempool has >= eventTriggerThreshold pending events (batch efficiency) 4. Cooldown elapsed since last trigger (prevent
    *      rapid-fire)
    */
  private def maybeEventTrigger[F[_]: Async, E, K](
    eventMempool: EventMempool[F, E, K],
    clusterStorage: ClusterStorage[F],
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
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
