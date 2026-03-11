package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Show
import cats.effect.Fiber
import cats.effect.kernel.{Async, Deferred, Ref}
import cats.effect.std.{Queue, Random, Supervisor}
import cats.kernel.{Eq, Next}
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream

/** Builds and wires together all consensus engine components.
  *
  * This is the entry point for creating a consensus engine. It assembles:
  *   - Command queue for FSM events
  *   - FSM instance for command routing
  *   - RoundRunner for facilitation and post-consensus logic
  *   - Background streams for peer registration and node state changes
  *
  * ==Streams==
  *
  * The engine runs three parallel streams:
  *
  * '''commandStream:''' Main event loop - takes commands from queue, routes to FSM
  * {{{
  *   Stream.repeatEval(queue.take).evalMap(fsm.handle)
  * }}}
  *
  * '''peerRegistrationStream:''' Watches for peers entering Observing state, collects their registration info
  *
  * '''leavingStream:''' Watches for node entering Leaving state, triggers withdrawal
  *
  * ==Usage==
  * {{{
  *   val built = ConsensusEventLoop.build(storage, creator, updater, ...)
  *   built.run.compile.drain // Start the engine
  *   built.manager.registerForConsensus(...) // External API
  * }}}
  *
  * @see
  *   ConsensusManager for external API
  * @see
  *   ConsensusFSM for command processing
  */
object ConsensusEventLoop {

  final case class BuiltConsensusLoop[F[_], Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    run: Stream[F, Unit],
    manager: ConsensusManager[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
    queue: Queue[F, ConsensusCommand]
  )

  def build[
    F[_]: Async: HasherSelector: Metrics: Random: Supervisor,
    Event,
    Key: Eq: Show: Next,
    Artifact: Eq,
    Ctx: Eq,
    Status,
    Outcome,
    Kind
  ](
    storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateCreator: ConsensusStateCreator[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateUpdater: ConsensusStateUpdater[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateRemover: ConsensusStateRemover[F, Key, Event, Artifact, Ctx, Status, Outcome, Kind],
    ops: ConsensusOps[Status, Kind],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusFunctions: ConsensusFunctions[F, Event, Key, Artifact, Ctx],
    consensusClient: ConsensusClient[F, Key, Outcome],
    config: ConsensusConfig
  )(
    implicit _key: monocle.Lens[Outcome, Key],
    _context: monocle.Lens[Outcome, Ctx],
    _artifact: monocle.Lens[Outcome, Signed[Artifact]],
    _trigger: monocle.Lens[Outcome, ConsensusTrigger]
  ): F[BuiltConsensusLoop[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]] =
    for {
      queue <- Queue.unbounded[F, ConsensusCommand]
      pending <- PendingTriggers.create[F]
      ctx <- ConsensusEngineContext.create(
        queue,
        pending,
        storage,
        stateCreator,
        stateUpdater,
        stateAdvancer,
        stateRemover,
        ops,
        nodeStorage,
        clusterStorage,
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLogger[F],
        config,
        consensusFunctions,
        consensusClient
      )
      stallDetector = new StallDetector[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](ctx)
      roundFibersRef <- Ref.of[F, List[Fiber[F, Throwable, Unit]]](Nil)
      cancelSignalRef <- Ref.of[F, Option[Deferred[F, Unit]]](None)
      roundRunner = new ConsensusRoundRunner[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        ctx,
        stallDetector,
        roundFibersRef,
        cancelSignalRef
      )
      fsm = new ConsensusFSM[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](ctx, roundRunner)
      manager <- ConsensusManager.make[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        queue,
        storage,
        nodeStorage
      )
    } yield {

      val commandStream: Stream[F, Unit] =
        Stream.repeatEval(queue.take).evalMap { cmd =>
          queue.size.flatMap(sz => Metrics[F].updateGauge("dag_consensus_command_queue_size", sz)) >>
            fsm.handle(cmd).handleErrorWith { err =>
              ctx.logger.error(err)(s"Unhandled error processing ${cmd.getClass.getSimpleName}, recovering") >>
                Metrics[F].incrementCounter("dag_consensus_command_error") >>
                (cmd match {
                  case _: ConsensusCommand.ConsensusFinished | ConsensusCommand.RoundCompleted =>
                    // Critical: if round-completion commands fail, FSM stays stuck in BUSY forever.
                    // Force round completion so the next round can start.
                    // Also offer TimeTick: the forced RoundCompleted calls completeRound without
                    // afterConsensusFinish, so no timer is scheduled for the next round. On solo nodes
                    // with no external events, this would deadlock consensus. The TimeTick fires once
                    // RoundCompleted sets isRunning=false, starting a new round from IDLE.
                    ctx.logger.warn("Forcing round completion after failed ConsensusFinished/RoundCompleted") >>
                      Metrics[F].incrementCounter("dag_consensus_forced_round_completion") >>
                      queue.offer(ConsensusCommand.RoundCompleted) >>
                      queue.offer(ConsensusCommand.TimeTick)
                  case _ => Async[F].unit
                })
            }
        }

      val peerRegistrationStream: Stream[F, Unit] =
        clusterStorage.peerChanges.mapFilter {
          case cats.data.Ior.Both(_, peer) if peer.state === NodeState.Observing => Some(peer)
          case cats.data.Ior.Right(peer) if peer.state === NodeState.Observing   => Some(peer)
          case _                                                                 => None
        }
          .filter(_.isResponsive)
          .evalMap(collectRegistration(consensusClient, storage))
          .handleErrorWith(e => Stream.eval(ctx.logger.error(e)("Peer registration failed")))

      val leavingStream: Stream[F, Unit] =
        nodeStorage.nodeStates
          .filter(_ === NodeState.Leaving)
          .evalMap(_ => manager.withdrawFromConsensus)
          .handleErrorWith(e => Stream.eval(ctx.logger.error(e)("Error handling Leaving state")))

      val run: Stream[F, Unit] =
        Stream(commandStream, peerRegistrationStream, leavingStream).parJoinUnbounded

      BuiltConsensusLoop(run, manager, queue)
    }

  private def collectRegistration[F[_]: Async: Metrics, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    consensusClient: ConsensusClient[F, Key, Outcome],
    storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
  )(peer: Peer): F[Unit] =
    consensusClient.getRegistration.run(peer).flatMap { reg =>
      reg.maybeKey.traverse_(key =>
        storage.registerPeer(peer.id, key) >>
          Metrics[F].incrementCounter("dag_consensus_peer_registered")
      )
    }
}
