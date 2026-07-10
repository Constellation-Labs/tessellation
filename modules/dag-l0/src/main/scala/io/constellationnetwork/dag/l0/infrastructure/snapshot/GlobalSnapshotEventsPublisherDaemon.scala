package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.dag.l0.domain.delegatedStake.{CreateDelegatedStakeOutput, DelegatedStakeOutput, WithdrawDelegatedStakeOutput}
import io.constellationnetwork.dag.l0.domain.nodeCollateral.{CreateNodeCollateralOutput, NodeCollateralOutput, WithdrawNodeCollateralOutput}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.snapshot.EventTriggerGuard
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.UpdateNodeParameters
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
    triggerEventConsensus: Option[F[Unit]],
    getLastFacilitatorCount: F[Int],
    consensusConfig: ConsensusConfig
  ): Daemon[F] = {
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

}
