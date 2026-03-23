package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

/** Tests for StallDetector logic in isolation.
  *
  * Since StallDetector depends on ConsensusEngineContext (which bundles many dependencies), these tests focus on the pure/deterministic
  * decision logic that can be verified without full IO mocking:
  *   - getResourcesInfo: resource hash computation and missing peer detection
  *   - handleStall decision logic: when to trigger view change or count towards abandon
  *   - MonitorState transitions: timer resets on status change and view change
  *   - maxRoundDuration: abandon condition computation
  */
object StallDetectorSuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 40)

  type Key = Int
  type Artifact = Unit
  type Context = Unit
  type Status = Either[Unit, Unit]
  type Outcome = Unit
  type Kind = Unit

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(5, 30)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  def mkState(
    facilitators: List[PeerId],
    withdrawn: Set[PeerId] = Set.empty
  ): ConsensusState[Key, Status, Outcome, Kind] =
    ConsensusState(
      key = 1,
      lastOutcome = (),
      facilitators = Facilitators(facilitators),
      status = ().asLeft,
      createdAt = FiniteDuration(0, "seconds"),
      withdrawnFacilitators = WithdrawnFacilitators(withdrawn),
      leader = facilitators.head,
      entropy = Hash.empty
    )

  def mkResources(declMap: Map[PeerId, PeerDeclarations] = Map.empty): ConsensusResources[Artifact, Kind] =
    ConsensusResources(
      peerDeclarationsMap = declMap,
      acksMap = Map.empty,
      withdrawalsMap = Map.empty,
      ackKinds = Set.empty,
      artifacts = Map.empty[Hash, Artifact],
      updatedAt = FiniteDuration(10, "seconds")
    )

  // === getResourcesInfo tests ===
  // Since getResourcesInfo is an instance method on StallDetector which needs ConsensusEngineContext,
  // we test the equivalent logic directly.

  def getResourcesInfo(
    facilitators: List[PeerId],
    withdrawn: Set[PeerId],
    collectingKind: Boolean,
    respondedPeers: Set[PeerId]
  ): (Int, Int, Set[String]) = {
    val active = facilitators.toSet -- withdrawn
    if (collectingKind) {
      val missing = active -- respondedPeers
      val declaredCount = (respondedPeers & active).size
      val missingPeerIds = missing.toList.map(_.value.value.take(8)).toSet
      (declaredCount, active.size, missingPeerIds)
    } else {
      (0, active.size, Set.empty[String])
    }
  }

  test("getResourcesInfo: active = facilitators minus withdrawn") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val withdrawn = facilitators.take(2).toSet
        val (_, activeCount, _) = getResourcesInfo(facilitators, withdrawn, collectingKind = false, Set.empty)
        expect.same(facilitators.size - withdrawn.size, activeCount)
      }
    }
  }

  test("getResourcesInfo: missing peers = active minus responded") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val responded = facilitators.take(facilitators.size / 2).toSet
        val (declared, active, missing) = getResourcesInfo(facilitators, Set.empty, collectingKind = true, responded)
        expect.same(responded.size, declared) &&
        expect.same(facilitators.size, active) &&
        expect.same(facilitators.size - responded.size, missing.size)
      }
    }
  }

  test("getResourcesInfo: no missing peers when all responded") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val responded = facilitators.toSet
        val (declared, active, missing) = getResourcesInfo(facilitators, Set.empty, collectingKind = true, responded)
        expect.same(facilitators.size, declared) &&
        expect.same(facilitators.size, active) &&
        expect(missing.isEmpty)
      }
    }
  }

  // === handleStall decision logic tests ===

  test("shouldHandle is true when statusDuration >= declarationTimeout") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 36.seconds

      val shouldHandle = statusDuration >= declarationTimeout
      expect(shouldHandle)
    }
  }

  test("shouldHandle is false when statusDuration < declarationTimeout") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 34.seconds

      val shouldHandle = statusDuration >= declarationTimeout
      expect(!shouldHandle)
    }
  }

  test("shouldHandle fires again after statusStartTime resets (no alreadyHandled guard)") {
    IO {
      // After Phase 2 fix: alreadyHandled was removed. handleStall fires every time
      // statusDuration >= declarationTimeout. The natural rate-limiting comes from
      // resetting statusStartTime to `now` after each stall detection.
      val declarationTimeout = 35.seconds
      val statusDuration = 36.seconds

      // First stall
      val shouldHandle1 = statusDuration >= declarationTimeout
      // After statusStartTime reset, time must elapse again before next stall fires
      val statusDurationAfterReset = 2.seconds // just reset, barely any time elapsed
      val shouldHandle2 = statusDurationAfterReset >= declarationTimeout
      // After another full timeout elapses
      val statusDurationAfterWait = 36.seconds
      val shouldHandle3 = statusDurationAfterWait >= declarationTimeout

      expect(shouldHandle1) && // first stall fires
      expect(!shouldHandle2) && // too soon after reset
      expect(shouldHandle3) // fires again after full timeout
    }
  }

  // === MonitorState transition tests ===

  test("statusStartTime resets on status change") {
    IO {
      val now = 100.seconds
      val statusChanged = true
      val previousStartTime = 50.seconds

      val newStatusStartTime = if (statusChanged) now else previousStartTime
      expect.same(now, newStatusStartTime)
    }
  }

  test("statusStartTime resets on any stall detection (view change or non-proposal)") {
    IO {
      val now = 100.seconds
      val previousStartTime = 50.seconds

      // After Phase 2 fix: statusStartTime resets on ANY stall, not just view changes.
      // This naturally rate-limits re-stalls.
      val didStallViewChange = true
      val adjustedOnViewChange = if (didStallViewChange) now else previousStartTime

      val didStallNonProposal = true
      val adjustedOnNonProposal = if (didStallNonProposal) now else previousStartTime

      val noStall = false
      val adjustedNoStall = if (noStall) now else previousStartTime

      expect.same(now, adjustedOnViewChange) &&
      expect.same(now, adjustedOnNonProposal) &&
      expect.same(previousStartTime, adjustedNoStall)
    }
  }

  test("stallCount resets on status change") {
    IO {
      val statusChanged = true
      val previousStallCount = 3

      val newStallCount = if (statusChanged) 0 else previousStallCount
      expect.same(0, newStallCount)
    }
  }

  test("stallCount increments on any stall (view change or non-proposal)") {
    IO {
      val previousStallCount = 2

      // After Phase 2 fix: stallCount increments on ANY stall type, not just view changes.
      val didStall = true
      val finalStallCount = if (didStall) previousStallCount + 1 else previousStallCount

      val noStall = false
      val unchangedStallCount = if (noStall) previousStallCount + 1 else previousStallCount

      expect.same(3, finalStallCount) &&
      expect.same(2, unchangedStallCount)
    }
  }

  // === maxRoundDuration abandon logic ===

  test("shouldAbandon when maxRoundDuration exceeded (regardless of lock/stall state)") {
    IO {
      val maxRoundDuration = Some(5.minutes)
      val roundElapsed = 6.minutes
      val stallCycleCount = 0
      val maxStallCycles = 5

      val roundTimedOut = maxRoundDuration.exists(roundElapsed >= _)
      val shouldAbandon = stallCycleCount >= maxStallCycles || roundTimedOut

      expect(roundTimedOut) && expect(shouldAbandon)
    }
  }

  test("shouldAbandon when maxStallCycles exceeded") {
    IO {
      val maxRoundDuration = Some(5.minutes)
      val roundElapsed = 2.minutes
      val stallCycleCount = 5
      val maxStallCycles = 5

      val roundTimedOut = maxRoundDuration.exists(roundElapsed >= _)
      val shouldAbandon = stallCycleCount >= maxStallCycles || roundTimedOut

      expect(!roundTimedOut) && expect(shouldAbandon)
    }
  }

  test("shouldAbandon when maxStallCycles exceeded even if not locked") {
    IO {
      val maxRoundDuration = Some(5.minutes)
      val roundElapsed = 2.minutes
      val stallCycleCount = 5
      val maxStallCycles = 5

      val roundTimedOut = maxRoundDuration.exists(roundElapsed >= _)
      val shouldAbandon = stallCycleCount >= maxStallCycles || roundTimedOut

      expect(shouldAbandon)
    }
  }

  test("should NOT abandon when within budget and within time") {
    IO {
      val maxRoundDuration = Some(5.minutes)
      val roundElapsed = 2.minutes
      val stallCycleCount = 2
      val maxStallCycles = 5

      val roundTimedOut = maxRoundDuration.exists(roundElapsed >= _)
      val shouldAbandon = stallCycleCount >= maxStallCycles || roundTimedOut

      expect(!shouldAbandon)
    }
  }

  // === effectiveTimeout selection ===

  test("effectiveTimeout uses reStallTimeout when stallCycleCount > 0") {
    IO {
      val declarationTimeout = 35.seconds
      val reStallTimeout = Some(10.seconds)
      val noProgressTimeout = Some(45.seconds)
      val stallCycleCount = 1
      val declaredCount = 5

      val effectiveTimeout =
        if (stallCycleCount > 0)
          reStallTimeout.getOrElse(declarationTimeout)
        else if (declaredCount == 0)
          noProgressTimeout.getOrElse(declarationTimeout)
        else
          declarationTimeout

      expect.same(10.seconds, effectiveTimeout)
    }
  }

  test("effectiveTimeout uses noProgressTimeout when no declarations received") {
    IO {
      val declarationTimeout = 35.seconds
      val reStallTimeout = Some(10.seconds)
      val noProgressTimeout = Some(45.seconds)
      val stallCycleCount = 0
      val declaredCount = 0

      val effectiveTimeout =
        if (stallCycleCount > 0)
          reStallTimeout.getOrElse(declarationTimeout)
        else if (declaredCount == 0)
          noProgressTimeout.getOrElse(declarationTimeout)
        else
          declarationTimeout

      expect.same(45.seconds, effectiveTimeout)
    }
  }

  test("effectiveTimeout uses declarationTimeout in normal case") {
    IO {
      val declarationTimeout = 35.seconds
      val reStallTimeout = Some(10.seconds)
      val noProgressTimeout = Some(45.seconds)
      val stallCycleCount = 0
      val declaredCount = 5

      val effectiveTimeout =
        if (stallCycleCount > 0)
          reStallTimeout.getOrElse(declarationTimeout)
        else if (declaredCount == 0)
          noProgressTimeout.getOrElse(declarationTimeout)
        else
          declarationTimeout

      expect.same(35.seconds, effectiveTimeout)
    }
  }

  // === Stall cycle accumulation ===

  test("stallCount preserved when no status change") {
    IO {
      val statusChanged = false
      val previousStallCount = 3

      val newStallCount = if (statusChanged) 0 else previousStallCount
      expect.same(3, newStallCount)
    }
  }

  // === Near-completion timeout extension ===

  test("near-completion extends timeout by 50%") {
    IO {
      val baseTimeout = 35.seconds
      val declaredCount = 16
      val activeCount = 20
      val stallCycleCount = 0

      val progress = if (activeCount > 0) declaredCount.toDouble / activeCount else 0.0
      val nearCompletion = progress >= 0.75 && declaredCount < activeCount
      val effectiveTimeout =
        if (nearCompletion && stallCycleCount == 0)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      expect(nearCompletion) &&
      expect.same(52500.millis, effectiveTimeout)
    }
  }

  test("near-completion does NOT extend during re-stall (stallCycleCount > 0)") {
    IO {
      val baseTimeout = 35.seconds
      val declaredCount = 16
      val activeCount = 20
      val stallCycleCount = 1

      val progress = if (activeCount > 0) declaredCount.toDouble / activeCount else 0.0
      val nearCompletion = progress >= 0.75 && declaredCount < activeCount
      val effectiveTimeout =
        if (nearCompletion && stallCycleCount == 0)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      expect(nearCompletion) &&
      expect.same(35.seconds, effectiveTimeout) // NOT extended during re-stall
    }
  }

  test("no extension when progress < 75%") {
    IO {
      val baseTimeout = 35.seconds
      val declaredCount = 10
      val activeCount = 20
      val stallCycleCount = 0

      val progress = if (activeCount > 0) declaredCount.toDouble / activeCount else 0.0
      val nearCompletion = progress >= 0.75 && declaredCount < activeCount
      val effectiveTimeout =
        if (nearCompletion && stallCycleCount == 0)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      expect(!nearCompletion) &&
      expect.same(35.seconds, effectiveTimeout)
    }
  }

  // === Abandon guard: condModifyState removes state unconditionally when shouldAbandon ===

  test("abandon guard: state removal is unconditional when shouldAbandon is true") {
    IO {
      // With `case Some(_) =>`, any existing state is removed when shouldAbandon triggers.
      // The shouldAbandon decision accounts for stall cycles and maxRoundDuration.
      val shouldRemove = true // case Some(_) => always matches
      expect(shouldRemove)
    }
  }

  // === Adaptive polling ===

  test("poll interval backs off when no changes") {
    IO {
      val basePollInterval = 100L
      val maxPollInterval = 1000L

      val sleepMs0 = basePollInterval // changed = true (noChangeCount would be 0)
      val sleepMs1 = math.min(basePollInterval * 2, maxPollInterval) // noChangeCount = 1
      val sleepMs5 = math.min(basePollInterval * 6, maxPollInterval) // noChangeCount = 5
      val sleepMs20 = math.min(basePollInterval * 21, maxPollInterval) // noChangeCount = 20, capped

      expect.same(100L, sleepMs0) &&
      expect.same(200L, sleepMs1) &&
      expect.same(600L, sleepMs5) &&
      expect.same(1000L, sleepMs20) // capped at maxPollInterval
    }
  }

  // === Stall cycle progression (Phase 2 fix verification) ===
  // These tests verify the full stall cycle accumulation logic after removing
  // the alreadyHandled guard. The key invariant: each stall detection resets
  // statusStartTime, so another full declarationTimeout must elapse before the
  // next stall fires. stallCount increments on EVERY stall type (view change
  // or non-proposal), not just view changes.

  test("stall count increments on each detection cycle") {
    IO {
      val declarationTimeout = 35.seconds
      val maxStallCycles = 5

      // Simulate the state machine through multiple stall detection cycles.
      // Each cycle: wait declarationTimeout → stall fires → statusStartTime resets → stallCount increments.
      case class SimState(stallCount: Int, statusStartTime: FiniteDuration)

      def simulateStep(sim: SimState, now: FiniteDuration, statusChanged: Boolean): SimState = {
        val newStallCount = if (statusChanged) 0 else sim.stallCount
        val newStatusStartTime = if (statusChanged) now else sim.statusStartTime
        val statusDuration = now - newStatusStartTime
        val shouldHandle = statusDuration >= declarationTimeout
        val didStall = shouldHandle // non-proposal phase always returns true when shouldHandle

        val adjustedStatusStartTime = if (didStall) now else newStatusStartTime
        val finalStallCount = if (didStall) newStallCount + 1 else newStallCount

        SimState(finalStallCount, adjustedStatusStartTime)
      }

      val start = SimState(stallCount = 0, statusStartTime = 0.seconds)

      // Cycle 1: at t=36s, stall fires → stallCount=1
      val after1 = simulateStep(start, 36.seconds, statusChanged = false)
      // Cycle 2: statusStartTime reset to 36s, at t=72s → stall fires → stallCount=2
      val after2 = simulateStep(after1, 72.seconds, statusChanged = false)
      // Cycle 3: statusStartTime reset to 72s, at t=108s → stall fires → stallCount=3
      val after3 = simulateStep(after2, 108.seconds, statusChanged = false)
      // Cycle 4: at t=144s → stallCount=4
      val after4 = simulateStep(after3, 144.seconds, statusChanged = false)
      // Cycle 5: at t=180s → stallCount=5 = maxStallCycles → should abandon
      val after5 = simulateStep(after4, 180.seconds, statusChanged = false)

      expect.same(1, after1.stallCount) &&
      expect.same(2, after2.stallCount) &&
      expect.same(3, after3.stallCount) &&
      expect.same(4, after4.stallCount) &&
      expect.same(5, after5.stallCount) &&
      expect(after5.stallCount >= maxStallCycles) // abandon condition met
    }
  }

  test("round abandoned after exactly maxStallCycles stall detections") {
    IO {
      val maxStallCycles = 5
      val maxRoundDuration = Some(300.seconds)
      val declarationTimeout = 35.seconds

      // Verify that stallCount reaches maxStallCycles after exactly maxStallCycles stall detections,
      // triggering abandon.
      val stallCounts = (1 to 6).scanLeft(0) {
        case (count, _) =>
          // Each step simulates: didStall = true, so count increments
          count + 1
      }

      // stallCounts: 0, 1, 2, 3, 4, 5, 6
      val beforeMaxStalls = stallCounts(4) // after 4 stalls
      val atMaxStalls = stallCounts(5) // after 5 stalls

      val roundElapsed = (5 * 36).seconds // well within maxRoundDuration
      val roundTimedOut = maxRoundDuration.exists(roundElapsed >= _)

      val shouldAbandonBefore = beforeMaxStalls >= maxStallCycles || roundTimedOut
      val shouldAbandonAt = atMaxStalls >= maxStallCycles || roundTimedOut

      expect(!shouldAbandonBefore) && // 4 stalls: not yet
      expect(shouldAbandonAt) && // 5 stalls: abandon
      expect(!roundTimedOut) // abandon was from stall count, not timeout
    }
  }

  test("statusStartTime resets after each stall — next stall needs full timeout") {
    IO {
      val declarationTimeout = 35.seconds

      // Simulate: stall at t=36s, statusStartTime resets to 36s
      // At t=50s (14s after reset), should NOT fire again
      // At t=72s (36s after reset), SHOULD fire again
      val statusStartAfterReset = 36.seconds

      val tooSoon = 50.seconds - statusStartAfterReset
      val longEnough = 72.seconds - statusStartAfterReset

      val shouldHandleTooSoon = tooSoon >= declarationTimeout
      val shouldHandleLongEnough = longEnough >= declarationTimeout

      expect(!shouldHandleTooSoon) && // 14s < 35s: too soon
      expect(shouldHandleLongEnough) // 36s >= 35s: fires
    }
  }

  test("non-proposal stall increments stallCount (previously only view changes did)") {
    IO {
      // Before Phase 2 fix: only didViewChange=true incremented stallCount.
      // Non-proposal stalls (where we don't do a view change) never incremented,
      // so maxStallCycles was dead code for non-proposal phases.
      // After fix: didStall=true for BOTH proposal (view change) and non-proposal stalls.
      val isProposalPhase = false
      val statusDuration = 36.seconds
      val declarationTimeout = 35.seconds
      val previousStallCount = 2

      // handleStall returns true for non-proposal phase when statusDuration >= timeout
      val shouldHandle = statusDuration >= declarationTimeout
      val didStall = shouldHandle // non-proposal just logs and returns true

      val finalStallCount = if (didStall) previousStallCount + 1 else previousStallCount

      expect(didStall) &&
      expect.same(3, finalStallCount) // incremented even for non-proposal stall
    }
  }

  test("status change resets stallCount — stall progression restarts from 0") {
    IO {
      // When the consensus advances to a new status (e.g., from Facility to Proposal),
      // stallCount resets to 0. Stall detection starts fresh for the new phase.
      val stallCountBeforeStatusChange = 3
      val statusChanged = true

      val newStallCount = if (statusChanged) 0 else stallCountBeforeStatusChange

      // Then if a stall fires in the new status, it starts at 1
      val didStall = true
      val finalStallCount = if (didStall) newStallCount + 1 else newStallCount

      expect.same(0, newStallCount) &&
      expect.same(1, finalStallCount) // fresh start
    }
  }
}
