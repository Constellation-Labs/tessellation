package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.Eq
import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.dag.l0.domain.delegatedStake.{CreateDelegatedStakeOutput, DelegatedStakeOutput, WithdrawDelegatedStakeOutput}
import io.constellationnetwork.dag.l0.domain.nodeCollateral.{CreateNodeCollateralOutput, NodeCollateralOutput, WithdrawNodeCollateralOutput}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.EventTriggerGuard
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.{Block, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

import eu.timepit.refined.auto._
import fs2.Stream
import io.circe.{Encoder => CirceEncoder}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalSnapshotEventsPublisherDaemon {

  private val AbsoluteMinimumEventTriggerParticipants = 2

  /** Local, non-consensus evidence that the responsive GL0 fleet has enough follower headroom for accelerated state-channel rounds. Unknown
    * peer keys count as unaligned. A slow or newly-joining fleet can therefore suppress acceleration, but never TimeTrigger production.
    * This value must not enter any proposal, signed bytes, membership derivation, or validation rule.
    */
  final case class FollowerHeadroom(aligned: Int, total: Int, required: Int) {
    def allowsAcceleration: Boolean = total > 0 && aligned >= required
  }

  object FollowerHeadroom {
    val unavailable: FollowerHeadroom = FollowerHeadroom(0, 1, 1)
  }

  /** Identity of the locally committed generation whose new state-channel arrivals may request acceleration. The snapshot hash is required:
    * IntegrationNet has re-produced the same ordinal after recovery, and ordinal-only identity would carry stale trigger intent across that
    * boundary. This type is in-memory only and is never serialized or hashed.
    */
  final case class EventTriggerGeneration(ordinal: SnapshotOrdinal, snapshotHash: Hash)
  object EventTriggerGeneration {
    implicit val eventTriggerGenerationEq: Eq[EventTriggerGeneration] = Eq.by(generation => (generation.ordinal, generation.snapshotHash))
  }

  final case class EventTriggerContext(
    generation: Option[EventTriggerGeneration],
    participatingFacilitators: Int,
    followerHeadroom: FollowerHeadroom
  )

  private[dag] final case class StateChannelTriggerIntent(
    generation: Option[EventTriggerGeneration],
    hashes: Set[Hash]
  ) {
    def record(currentGeneration: Option[EventTriggerGeneration], hash: Hash): StateChannelTriggerIntent = {
      val current = if (generation === currentGeneration) this else StateChannelTriggerIntent(currentGeneration, Set.empty)
      current.copy(hashes = current.hashes + hash)
    }

    def consume(consumed: Set[Hash]): StateChannelTriggerIntent =
      copy(hashes = hashes -- consumed)
  }

  private[dag] object StateChannelTriggerIntent {
    val empty: StateChannelTriggerIntent = StateChannelTriggerIntent(None, Set.empty)
  }

  private sealed abstract class IntakeResult(val label: String)
  private object IntakeResult {
    final case class Inserted(hash: Hash) extends IntakeResult("inserted")
    case object Duplicate extends IntakeResult("duplicate")
    case object Rejected extends IntakeResult("rejected")
  }

  /** Compute local follower alignment against the next round after the latest committed outcome.
    *
    * The denominator is every responsive in-session peer plus self, not merely peers already in `Ready`. Counting only Ready peers would
    * make the gate reopen as soon as lagging followers transition to Observing/WaitingForDownload, recreating the starvation loop. A peer
    * is aligned only after a keyed consensus rumor proves it has reached the expected next-round key.
    */
  private[dag] def followerHeadroom(
    expectedKey: SnapshotOrdinal,
    responsivePeerIds: Set[PeerId],
    peerCurrentKeys: Map[PeerId, SnapshotOrdinal],
    selfId: PeerId
  ): FollowerHeadroom = {
    val population = responsivePeerIds + selfId
    val aligned = population.count(pid => pid === selfId || peerCurrentKeys.get(pid).exists(_ >= expectedKey))
    FollowerHeadroom(aligned, population.size, QuorumPolicy.supermajority(population.size))
  }

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

  def make[F[_]: Async: Supervisor: HasherSelector: Metrics: SecurityProvider](
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
    getEventTriggerContext: F[EventTriggerContext],
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
      (Ref.of[F, Long](0L), Ref.of[F, StateChannelTriggerIntent](StateChannelTriggerIntent.empty)).tupled.flatMap {
        case (lastTriggerRef, stateChannelIntentRef) =>
          HasherSelector[F].withCurrent { implicit hasher =>
            events.evalMap { event =>
              val isStateChannel = event match {
                case _: StateChannelEvent => true
                case _                    => false
              }
              val eventClass = if (isStateChannel) "state_channel" else "immediate"

              signAndPublish(event, keyPair, eventMempool, eventGossipDaemon, logger).flatMap { intake =>
                val observeIntake =
                  Metrics[F].incrementCounter(
                    "dag_event_trigger_intake_total",
                    Seq(
                      Metrics.unsafeLabelName("event_class") -> eventClass,
                      Metrics.unsafeLabelName("outcome") -> intake.label
                    )
                  )

                val schedule = intake match {
                  case IntakeResult.Inserted(hash) =>
                    getEventTriggerContext.flatMap { context =>
                      def signerHeadroomDecision: F[String] =
                        logger.debug(
                          s"EventTrigger skipped: last GL0 artifact had ${context.participatingFacilitators} current facilitator signer(s), " +
                            s"required=$requiredParticipants; TimeTrigger remains active"
                        ) >> "signer_headroom".pure[F]

                      val trigger =
                        if (!isStateChannel) {
                          if (!hasSufficientEventTriggerParticipation(context.participatingFacilitators, requiredParticipants))
                            signerHeadroomDecision
                          else
                            EventTriggerGuard
                              .evaluate(
                                triggerEventConsensus,
                                context.participatingFacilitators.pure[F],
                                lastTriggerRef,
                                logger,
                                pendingEventCount = 1,
                                threshold = 1,
                                cooldown = eventTriggerCooldown
                              )
                              .map(_.label)
                        } else
                          stateChannelIntentRef.modify { state =>
                            val next = state.record(context.generation, hash)
                            (next, next.hashes)
                          }.flatMap { capturedIntent =>
                            val pending = capturedIntent.size
                            val headroom = context.followerHeadroom
                            val observeHeadroom =
                              Metrics[F].updateGauge("dag_event_trigger_follower_aligned_peers", headroom.aligned.toLong) >>
                                Metrics[F].updateGauge("dag_event_trigger_follower_total_peers", headroom.total.toLong) >>
                                Metrics[F].updateGauge("dag_event_trigger_follower_required_peers", headroom.required.toLong) >>
                                Metrics[F].updateGauge("dag_event_trigger_state_channel_intent_pending", pending.toLong)

                            val decision =
                              if (!hasSufficientEventTriggerParticipation(context.participatingFacilitators, requiredParticipants))
                                signerHeadroomDecision
                              else if (!headroom.allowsAcceleration)
                                "follower_headroom".pure[F]
                              else
                                EventTriggerGuard
                                  .evaluate(
                                    triggerEventConsensus,
                                    context.participatingFacilitators.pure[F],
                                    lastTriggerRef,
                                    logger,
                                    pending,
                                    eventTriggerThreshold,
                                    eventTriggerCooldown
                                  )
                                  .flatMap {
                                    case EventTriggerGuard.Decision.Fired =>
                                      stateChannelIntentRef.modify { current =>
                                        val remaining = current.consume(capturedIntent)
                                        (remaining, remaining.hashes.size)
                                      }
                                        .flatMap(remaining =>
                                          Metrics[F]
                                            .updateGauge("dag_event_trigger_state_channel_intent_pending", remaining.toLong)
                                        )
                                        .as(EventTriggerGuard.Decision.Fired.label)
                                    case other => other.label.pure[F]
                                  }

                            observeHeadroom >> decision
                          }

                      trigger.flatMap { outcome =>
                        Metrics[F].incrementCounter(
                          "dag_event_trigger_decision_total",
                          Seq(
                            Metrics.unsafeLabelName("event_class") -> eventClass,
                            Metrics.unsafeLabelName("outcome") -> outcome
                          )
                        )
                      }
                    }
                  case _ => Async[F].unit
                }

                observeIntake >> schedule
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
  )(implicit hasher: Hasher[F], signed: CirceEncoder[E]): F[IntakeResult] =
    hasher.hash(event).flatMap { triggerIntentHash =>
      Signed.forAsyncHasher[F, E](event, keyPair).flatMap { signedEvent =>
        signedEvent.toHashed.flatMap { hashedEvent =>
          eventMempool.addWithStatus(signedEvent).flatMap {
            case Right(result) if result.inserted =>
              // The outer Signed[E] uses randomized ECDSA and therefore has a different envelope hash when identical input is resubmitted.
              // Trigger intent is keyed by the underlying event hash so semantic re-delivery cannot inflate a state-channel batch.
              eventGossipDaemon.publish(hashedEvent).as[IntakeResult](IntakeResult.Inserted(triggerIntentHash))
            case Right(_) =>
              IntakeResult.Duplicate.pure[F].widen[IntakeResult]
            case Left(reason) =>
              logger
                .warn(s"Failed to add event to mempool: ${event.getClass.getSimpleName}, reason=$reason")
                .as[IntakeResult](IntakeResult.Rejected)
          }
        }
      }
    }

}
