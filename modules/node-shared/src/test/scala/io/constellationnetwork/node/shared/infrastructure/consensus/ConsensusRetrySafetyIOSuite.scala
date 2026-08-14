package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.TimeoutReason
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusFSM, StateTransitions}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object ConsensusRetrySafetyIOSuite extends SimpleIOSuite {

  private val peer = PeerId(Hex("01" * 64))
  private val peer2 = PeerId(Hex("02" * 64))

  private val emptyResources = ConsensusResources[Unit, String](
    peerDeclarationsMap = Map.empty,
    acksMap = Map.empty,
    withdrawalsMap = Map.empty,
    ackKinds = Set.empty,
    artifacts = Map.empty,
    updatedAt = 0.seconds
  )

  test("a failing serialized view-change request is contained and re-arms monitoring") {
    for {
      observedError <- Ref.of[IO, Option[String]](None)
      rearmCount <- Ref.of[IO, Int](0)
      _ <- ConsensusEventLoop.recoverViewChangeRequestFailure[IO](
        IO.raiseError(new RuntimeException("voter failed")),
        error => observedError.set(error.getMessage.some),
        rearmCount.update(_ + 1)
      )
      observed <- observedError.get
      rearms <- rearmCount.get
    } yield expect(observed.contains("voter failed")).and(expect(rearms == 1))
  }

  test("view-change recovery logging and monitor failures cannot escape the command handler") {
    ConsensusEventLoop
      .recoverViewChangeRequestFailure[IO](
        IO.raiseError(new RuntimeException("request")),
        _ => IO.raiseError(new RuntimeException("logger")),
        IO.raiseError(new RuntimeException("monitor"))
      )
      .as(success)
  }

  test("queue-depth observation failure cannot consume a dequeued consensus command") {
    for {
      dispatched <- Ref.of[IO, Boolean](false)
      _ <- ConsensusEventLoop.observeQueueDepthThenDispatch[IO](IO.raiseError(new RuntimeException("metrics")))(dispatched.set(true))
      wasDispatched <- dispatched.get
    } yield expect(wasDispatched)
  }

  pureTest("planned initialization resumes from every partial-install lifecycle state only") {
    expect(ConsensusEventLoop.plannedInitializationRetryableState(NodeState.Observing)) &&
    expect(ConsensusEventLoop.plannedInitializationRetryableState(NodeState.WaitingForReady)) &&
    expect(ConsensusEventLoop.plannedInitializationRetryableState(NodeState.Ready)) &&
    expect(!ConsensusEventLoop.plannedInitializationRetryableState(NodeState.WaitingForDownload)) &&
    expect(!ConsensusEventLoop.plannedInitializationRetryableState(NodeState.Leaving))
  }

  pureTest("planned initialization node-state transition is idempotent and fail-closed") {
    import StateTransitions.PlannedInitializationStateDisposition._

    expect.same(EnterWaitingForReady, StateTransitions.plannedInitializationStateDisposition(NodeState.Observing)) &&
    expect.same(ResumeAndRepublish, StateTransitions.plannedInitializationStateDisposition(NodeState.WaitingForReady)) &&
    expect.same(ResumeAndRepublish, StateTransitions.plannedInitializationStateDisposition(NodeState.Ready)) &&
    expect.same(Reject, StateTransitions.plannedInitializationStateDisposition(NodeState.WaitingForDownload)) &&
    expect.same(Reject, StateTransitions.plannedInitializationStateDisposition(NodeState.Leaving))
  }

  test("a partial round-start failure cleans local ownership and publishes the only retry despite telemetry failure") {
    for {
      idle <- Ref.of[IO, Boolean](false)
      cleanupCount <- Ref.of[IO, Int](0)
      retryCount <- Ref.of[IO, Int](0)
      _ <- ConsensusFSM.recoverRoundStartFailure[IO](
        cleanupCount.update(_ + 1) >> IO.raiseError(new RuntimeException("cleanup")),
        idle.set(true),
        IO.raiseError(new RuntimeException("logger")),
        IO.unit,
        retryCount.update(_ + 1)
      )
      wasIdle <- idle.get
      cleanups <- cleanupCount.get
      retries <- retryCount.get
    } yield expect.all(wasIdle, cleanups == 1, retries == 1)
  }

  test("cancellation cannot consume a partial round-start retry") {
    for {
      pauseEntered <- cats.effect.Deferred[IO, Unit]
      retried <- Ref.of[IO, Boolean](false)
      fiber <- ConsensusFSM
        .recoverRoundStartFailure[IO](
          IO.unit,
          IO.unit,
          IO.unit,
          pauseEntered.complete(()).void >> IO.sleep(20.millis),
          retried.set(true)
        )
        .start
      _ <- pauseEntered.get
      _ <- fiber.cancel
      retryPublished <- retried.get
    } yield expect(retryPublished)
  }

  test("gate opening is atomic with exact first-round establishment and a failed generation remains retryable") {
    val committee = SortedSet(peer, peer2)
    val wrongCommittee = SortedSet(peer)

    for {
      gate <- FirstRoundStartGate.make[IO, Long](initiallyHeld = true)
      permit <- gate.arm(10L)
      running <- Ref.of[IO, Boolean](false)
      inspection <- Ref.of[IO, ConsensusFSM.FirstRoundInspection[Long]](
        ConsensusFSM.FirstRoundInspection(false, none, none)
      )
      cleanups <- Ref.of[IO, Int](0)
      first <- gate
        .releaseAfter(permit)(
          ConsensusFSM.establishFirstRound[IO, Long](
            10L,
            committee,
            running.get,
            running.set(true) >> inspection.set(ConsensusFSM.FirstRoundInspection(true, 10L.some, wrongCommittee.some)),
            inspection.get,
            cleanups.update(_ + 1) >> running.set(false)
          )
        )
        .attempt
      heldAfterFailure <- gate.isPending(permit)
      second <- gate.releaseAfter(permit)(
        ConsensusFSM.establishFirstRound[IO, Long](
          10L,
          committee,
          running.get,
          running.set(true) >> inspection.set(ConsensusFSM.FirstRoundInspection(true, 10L.some, committee.some)),
          inspection.get,
          cleanups.update(_ + 1) >> running.set(false)
        )
      )
      openAfterSuccess <- gate.isHeld.map(held => !held)
      cleanupCount <- cleanups.get
    } yield expect.all(first.isLeft, heldAfterFailure, second, openAfterSuccess, cleanupCount == 1)
  }

  test("abandon errors are contained and monitor re-arm is attempted after success or failure") {
    for {
      failedRearms <- Ref.of[IO, Int](0)
      errorObserved <- Ref.of[IO, Boolean](false)
      _ <- ConsensusEventLoop.containAbandonAndRearm[IO](
        IO.raiseError(new RuntimeException("abandon")),
        _ => errorObserved.set(true) >> IO.raiseError(new RuntimeException("logger")),
        IO.unit,
        failedRearms.update(_ + 1) >> IO.raiseError(new RuntimeException("monitor"))
      )
      failedCount <- failedRearms.get
      observed <- errorObserved.get
      successfulRearms <- Ref.of[IO, Int](0)
      _ <- ConsensusEventLoop.containAbandonAndRearm[IO](IO.unit, _ => IO.unit, IO.unit, successfulRearms.update(_ + 1))
      successfulCount <- successfulRearms.get
    } yield expect(observed).and(expect(failedCount == 1)).and(expect(successfulCount == 1))
  }

  test("an abandon tail failure releases Busy only after state removal and retries only from Ready") {
    for {
      completions <- Ref.of[IO, Int](0)
      ticks <- Ref.of[IO, Int](0)
      cleanups <- Ref.of[IO, Int](0)
      _ <- ConsensusEventLoop.recoverFailedAbandonAfterStateRemoval[IO](
        false.pure[IO],
        io.constellationnetwork.schema.node.NodeState.Ready.pure[IO],
        cleanups.update(_ + 1),
        completions.update(_ + 1),
        ticks.update(_ + 1)
      )
      afterReadyCompletions <- completions.get
      afterReadyTicks <- ticks.get
      _ <- ConsensusEventLoop.recoverFailedAbandonAfterStateRemoval[IO](
        true.pure[IO],
        io.constellationnetwork.schema.node.NodeState.Ready.pure[IO],
        cleanups.update(_ + 1),
        completions.update(_ + 1),
        ticks.update(_ + 1)
      )
      _ <- ConsensusEventLoop.recoverFailedAbandonAfterStateRemoval[IO](
        false.pure[IO],
        io.constellationnetwork.schema.node.NodeState.WaitingForDownload.pure[IO],
        cleanups.update(_ + 1),
        completions.update(_ + 1),
        ticks.update(_ + 1)
      )
      finalCompletions <- completions.get
      finalTicks <- ticks.get
      finalCleanups <- cleanups.get
    } yield
      expect(afterReadyCompletions == 1) &&
        expect(afterReadyTicks == 1) &&
        expect(finalCompletions == 2) &&
        expect(finalTicks == 1) &&
        expect(finalCleanups == 2)
  }

  test("abandon tail recovery is total when completion, state, or retry effects fail") {
    ConsensusEventLoop
      .recoverFailedAbandonAfterStateRemoval[IO](
        false.pure[IO],
        io.constellationnetwork.schema.node.NodeState.Ready.pure[IO],
        IO.raiseError(new RuntimeException("cleanup")),
        IO.raiseError(new RuntimeException("completion queue")),
        IO.raiseError(new RuntimeException("tick queue"))
      ) >>
      ConsensusEventLoop
        .recoverFailedAbandonAfterStateRemoval[IO](
          IO.raiseError(new RuntimeException("state read")),
          IO.raiseError(new RuntimeException("node state")),
          IO.raiseError(new RuntimeException("cleanup")),
          IO.raiseError(new RuntimeException("completion queue")),
          IO.raiseError(new RuntimeException("tick queue"))
        )
        .as(success)
  }

  test("auxiliary monitor votes do not invalidate an abandon decision") {
    val afterMonitorVotes = emptyResources.copy(
      admissionVotes = Map(peer -> Map.empty),
      evictionVotes = Map(peer -> Map.empty),
      viewChangeVotes = Map((0L, 1L) -> Map.empty),
      timeoutVotes = Map((0L, 1L) -> Map.empty),
      updatedAt = 1.second
    )

    val initialGeneration = 5L
    val generationAfterVotes =
      if (ConsensusStorage.attemptProgressChanged(emptyResources, afterMonitorVotes)) initialGeneration + 1L else initialGeneration

    IO.pure(
      expect(generationAfterVotes == initialGeneration)
        .and(expect(AbandonmentTracker.isCurrentDecision(9L, initialGeneration, 9L, generationAfterVotes)))
    )
  }

  test("new phase evidence invalidates an older abandon decision") {
    val afterPeerDeclaration = emptyResources.copy(
      peerDeclarationsMap = Map(peer -> PeerDeclarations.empty.copy(facility = None)),
      updatedAt = 1.second
    )
    val initialGeneration = 5L
    val generationAfterDeclaration =
      if (ConsensusStorage.attemptProgressChanged(emptyResources, afterPeerDeclaration)) initialGeneration + 1L else initialGeneration

    IO.pure(
      expect(generationAfterDeclaration == initialGeneration + 1L)
        .and(expect(!AbandonmentTracker.isCurrentDecision(9L, initialGeneration, 9L, generationAfterDeclaration)))
    )
  }

  test("serialized pacemaker emission makes a pre-assembly abandon stale and leaves assembly FIFO-runnable") {
    type Command = ConsensusCommand[Long, Unit, Unit, Unit]
    val attemptId = 9L
    val progressGeneration = 5L
    val request: Command = ConsensusCommand.RequestViewChange(
      key = 42L,
      expectedFromView = 0L,
      expectedAttemptId = attemptId,
      expectedProgressGeneration = progressGeneration,
      reason = TimeoutReason.NoProgress
    )
    val abandonSampledBeforeEmission: Command = ConsensusCommand.AbandonRound(
      key = 42L,
      reason = AbandonReason.MaxStalls(3),
      expectedAttemptId = attemptId,
      expectedResourceGeneration = progressGeneration
    )

    for {
      queue <- Queue.unbounded[IO, Command]
      generation <- Ref.of[IO, Long](progressGeneration)
      // Tick 1 gave the newly-accepted request its one-cycle grace. Tick 2 can run
      // before the command loop drains RequestViewChange and queues an abandon from
      // the old generation, producing Request -> Abandon in FIFO order.
      _ <- queue.offer(request)
      _ <- queue.offer(abandonSampledBeforeEmission)
      first <- queue.take
      _ <- first match {
        case _: ConsensusCommand.RequestViewChange[_] =>
          // Successful local VCV+TC emission explicitly advances the progress epoch,
          // then appends the two assembly commands. The already-queued abandon stays
          // ahead in FIFO but can no longer remove the state they require.
          generation.update(_ + 1L) >>
            queue.offer(ConsensusCommand.CheckViewChangeAssembly(42L)) >>
            queue.offer(ConsensusCommand.CheckTimeoutCertificateAssembly(42L))
        case _ => IO.raiseError(new IllegalStateException("expected RequestViewChange first"))
      }
      second <- queue.take
      currentGeneration <- generation.get
      staleAbandon = second match {
        case ConsensusCommand.AbandonRound(_, _, expectedAttempt, expectedGeneration) =>
          !AbandonmentTracker.isCurrentDecision(expectedAttempt, expectedGeneration, attemptId, currentGeneration)
        case _ => true
      }
      assembly1 <- queue.take
      assembly2 <- queue.take
      assemblyOpportunity =
        assembly1 == ConsensusCommand.CheckViewChangeAssembly(42L) &&
          assembly2 == ConsensusCommand.CheckTimeoutCertificateAssembly(42L)
      // If those checks do not certify a transition, the re-armed next tick sees a
      // duplicate (not new) request and must be free to abandon from the new epoch.
      nextTickShouldAbandon = StallDetector.shouldAbandonThisMonitorTick(
        abandonRequested = true,
        isLagging = false,
        sameKeyRestartUnsafe = false,
        newPacemakerRequestEnqueued = false
      )
      nextAbandon = ConsensusCommand.AbandonRound[Long](
        42L,
        AbandonReason.MaxStalls(4),
        attemptId,
        currentGeneration
      )
      nextAbandonCurrent = nextAbandon match {
        case ConsensusCommand.AbandonRound(_, _, expectedAttempt, expectedGeneration) =>
          AbandonmentTracker.isCurrentDecision(expectedAttempt, expectedGeneration, attemptId, currentGeneration)
      }
    } yield expect(staleAbandon) && expect(assemblyOpportunity) && expect(nextTickShouldAbandon) && expect(nextAbandonCurrent)
  }
}
