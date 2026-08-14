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
import io.constellationnetwork.schema.peer.PeerId
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

  private val AbsoluteMinimumEventTriggerParticipants = 2

  /** Count locally observed signers that still hold a seat in the carried outcome.
    *
    * `GlobalConsensusOutcome.facilitators.size` is membership, not participation. Once GL0 retains silent Tier-1 seats, using membership as
    * the input to the solo-producer EventTrigger guard lets one actual signer rapid-fire event rounds whenever at least one silent seat is
    * retained. Intersecting with the carried membership also fails closed for a synthetic recovery outcome whose historical checkpoint
    * proofs do not belong to its recovery-seeded committee.
    *
    * This value is local pacing evidence only. It must never enter proposal validation, membership derivation, snapshot bytes, or a state
    * proof because valid artifact proof subsets can differ between nodes.
    *
    * Event-driven production resumes only after the number of actual participants reaches the existing bootstrap-completion threshold (with
    * an absolute minimum of two). This gives downloading peers TimeTrigger-paced catch-up through the same configured numeric threshold
    * operators already choose for the expected steady-state committee, without adding another knob or changing that config value.
    */
  private[dag] def participatingFacilitatorCount(facilitators: Set[PeerId], proofSigners: Set[PeerId]): Int =
    facilitators.intersect(proofSigners).size

  private[dag] def minimumEventTriggerParticipants(bootstrapCompleteProofsThreshold: Int): Int =
    math.max(AbsoluteMinimumEventTriggerParticipants, bootstrapCompleteProofsThreshold)

  private[dag] def hasSufficientEventTriggerParticipation(
    participatingFacilitatorCount: Int,
    minimumEventTriggerParticipants: Int
  ): Boolean =
    participatingFacilitatorCount >= minimumEventTriggerParticipants

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
    getLastParticipatingFacilitatorCount: F[Int],
    consensusConfig: ConsensusConfig
  ): Daemon[F] = {
    val eventTriggerThreshold = consensusConfig.eventTriggerThreshold
    val eventTriggerCooldown = consensusConfig.eventTriggerCooldown
    val requiredParticipants = minimumEventTriggerParticipants(consensusConfig.bootstrapCompleteProofsThreshold)
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
              getLastParticipatingFacilitatorCount.flatMap { participantCount =>
                if (!hasSufficientEventTriggerParticipation(participantCount, requiredParticipants))
                  logger.debug(
                    s"EventTrigger skipped: last GL0 artifact had $participantCount current facilitator signer(s), " +
                      s"required=$requiredParticipants; TimeTrigger remains active"
                  )
                else
                  EventTriggerGuard(
                    eventMempool,
                    triggerEventConsensus,
                    participantCount.pure[F],
                    lastTriggerRef,
                    logger,
                    eventTriggerThreshold,
                    eventTriggerCooldown
                  )
              }
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
