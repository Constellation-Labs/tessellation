package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, FirstRoundStartGate}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}

import weaver.SimpleIOSuite

object FirstRoundStartGateSuite extends SimpleIOSuite {

  pureTest("all ordinary round trigger variants are covered by the recovery gate") {
    val commands: List[ConsensusCommand[Int, Nothing, Nothing, Nothing]] = List(
      ConsensusCommand.StartRound(None),
      ConsensusCommand.StartRound(TimeTrigger.some),
      ConsensusCommand.StartRound(EventTrigger.some),
      ConsensusCommand.TimeTick,
      ConsensusCommand.FacilitateByEvent
    )

    expect(commands.forall(FirstRoundStartGate.isOrdinaryStartCommand)) &&
    expect(!FirstRoundStartGate.isOrdinaryStartCommand(ConsensusCommand.CheckUpdate(1)))
  }

  test("held gate releases only for the exact key and generation and never reuses a generation") {
    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = true)
      first <- gate.arm(10)
      heldInitially <- gate.isHeld
      pendingInitially <- gate.isPending(first)
      scheduled <- cats.effect.Ref.of[IO, Int](0)
      wrongKey <- gate.releaseAfter(first.copy(key = 11))(scheduled.update(_ + 1))
      wrongGeneration <- gate.releaseAfter(first.copy(generation = first.generation + 1L))(scheduled.update(_ + 1))
      stillHeld <- gate.isHeld
      released <- gate.releaseAfter(first)(scheduled.update(_ + 1))
      open <- gate.isHeld.map(!_)
      second <- gate.arm(10)
      staleOldRelease <- gate.releaseAfter(first)(scheduled.update(_ + 1))
      heldAfterStale <- gate.isHeld
      releasedSecond <- gate.releaseAfter(second)(scheduled.update(_ + 1))
      scheduledCount <- scheduled.get
    } yield
      expect.all(
        heldInitially,
        pendingInitially,
        !wrongKey,
        !wrongGeneration,
        stillHeld,
        released,
        open,
        second.generation > first.generation,
        !staleOldRelease,
        heldAfterStale,
        releasedSecond,
        scheduledCount == 2
      )
  }

  test("a failed first-round schedule leaves the exact permit held and retryable") {
    val schedulingFailure = new RuntimeException("schedule failed")

    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = true)
      permit <- gate.arm(10)
      failed <- gate.releaseAfter(permit)(IO.raiseError(schedulingFailure)).attempt
      heldAfterFailure <- gate.isHeld
      released <- gate.releaseAfter(permit)(IO.unit)
      openAfterRetry <- gate.isHeld.map(!_)
    } yield expect.all(failed == Left(schedulingFailure), heldAfterFailure, released, openAfterRetry)
  }

  test("cancellation keeps the exact first-round permit held and retryable") {
    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = true)
      permit <- gate.arm(10)
      schedulingStarted <- cats.effect.Deferred[IO, Unit]
      releaseFiber <- gate
        .releaseAfter(permit)(schedulingStarted.complete(()).void >> IO.never)
        .start
      _ <- schedulingStarted.get
      _ <- releaseFiber.cancel
      heldAfterCancellation <- gate.isHeld
      pendingAfterCancellation <- gate.isPending(permit)
      releasedOnRetry <- gate.releaseAfter(permit)(IO.unit)
      openAfterRetry <- gate.isHeld.map(!_)
    } yield expect.all(heldAfterCancellation, pendingAfterCancellation, releasedOnRetry, openAfterRetry)
  }

  test("a validated newer initialization opens a stale hold but cannot open the current generation") {
    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = false)
      stale <- gate.arm(10)
      sameKeyOpened <- gate.openIfSupersededBy(10)
      stillPending <- gate.isPending(stale)
      newerKeyOpened <- gate.openIfSupersededBy(11)
      stalePendingAfterOpen <- gate.isPending(stale)
      current <- gate.arm(11)
      staleRelease <- gate.releaseAfter(stale)(IO.unit)
      currentPending <- gate.isPending(current)
      currentKeyOpened <- gate.openIfSupersededBy(11)
      currentStillPending <- gate.isPending(current)
    } yield
      expect.all(
        !sameKeyOpened,
        stillPending,
        newerKeyOpened,
        !stalePendingAfterOpen,
        !staleRelease,
        currentPending,
        !currentKeyOpened,
        currentStillPending,
        current.generation > stale.generation
      )
  }
}
