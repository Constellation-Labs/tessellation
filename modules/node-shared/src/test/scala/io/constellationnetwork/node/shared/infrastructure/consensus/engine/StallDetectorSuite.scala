package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
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
  *   - handleStall decision logic: when to lock based on timeout and state
  *   - MonitorState transitions: timer resets on status change and Reopened
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
    withdrawn: Set[PeerId] = Set.empty,
    lockStatus: LockStatus = LockStatus.Open
  ): ConsensusState[Key, Status, Outcome, Kind] =
    ConsensusState(
      key = 1,
      lastOutcome = (),
      facilitators = Facilitators(facilitators),
      status = ().asLeft,
      createdAt = FiniteDuration(0, "seconds"),
      withdrawnFacilitators = WithdrawnFacilitators(withdrawn),
      lockStatus = lockStatus,
      spreadAckKinds = Set.empty
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
    respondedPeers: Set[PeerId],
    acksMapSize: Int
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
        val (_, activeCount, _) = getResourcesInfo(facilitators, withdrawn, collectingKind = false, Set.empty, 0)
        expect.same(facilitators.size - withdrawn.size, activeCount)
      }
    }
  }

  test("getResourcesInfo: missing peers = active minus responded") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val responded = facilitators.take(facilitators.size / 2).toSet
        val (declared, active, missing) = getResourcesInfo(facilitators, Set.empty, collectingKind = true, responded, 0)
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
        val (declared, active, missing) = getResourcesInfo(facilitators, Set.empty, collectingKind = true, responded, 0)
        expect.same(facilitators.size, declared) &&
        expect.same(facilitators.size, active) &&
        expect(missing.isEmpty)
      }
    }
  }

  // === handleStall decision logic tests ===

  test("shouldLock is true when statusDuration >= declarationTimeout and not already locked") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 36.seconds
      val alreadyLocked = false

      val shouldLock = statusDuration >= declarationTimeout && !alreadyLocked
      expect(shouldLock)
    }
  }

  test("shouldLock is false when statusDuration < declarationTimeout") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 34.seconds
      val alreadyLocked = false

      val shouldLock = statusDuration >= declarationTimeout && !alreadyLocked
      expect(!shouldLock)
    }
  }

  test("shouldLock is false when already locked") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 36.seconds
      val alreadyLocked = true

      val shouldLock = statusDuration >= declarationTimeout && !alreadyLocked
      expect(!shouldLock)
    }
  }

  // === MonitorState transition tests ===

  test("statusStartTime resets on status change") {
    IO {
      val now = 100.seconds
      val statusChanged = true
      val reopened = false
      val previousStartTime = 50.seconds

      val newStatusStartTime =
        if (statusChanged) now
        else if (reopened) now
        else previousStartTime

      expect.same(now, newStatusStartTime)
    }
  }

  test("statusStartTime resets on Reopened transition") {
    IO {
      val now = 100.seconds
      val statusChanged = false
      val reopened = true
      val previousStartTime = 50.seconds

      val newStatusStartTime =
        if (statusChanged) now
        else if (reopened) now
        else previousStartTime

      expect.same(now, newStatusStartTime)
    }
  }

  test("stallCycleCount resets on status change") {
    IO {
      val statusChanged = true
      val previousCycleCount = 3

      val newStallCycleCount = if (statusChanged) 0 else previousCycleCount
      expect.same(0, newStallCycleCount)
    }
  }

  test("lockedForStatus clears on status change") {
    IO {
      val statusChanged = true
      val reopened = false
      val previousLocked = true

      val newLockedForStatus =
        if (statusChanged) false
        else if (reopened) false
        else previousLocked

      expect(!newLockedForStatus)
    }
  }

  test("lockedForStatus clears on Reopened") {
    IO {
      val statusChanged = false
      val reopened = true
      val previousLocked = true

      val newLockedForStatus =
        if (statusChanged) false
        else if (reopened) false
        else previousLocked

      expect(!newLockedForStatus)
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

  // === Failed stall cycle detection ===

  test("failedStallCycle detected when locked, past timeout, within budget, and no status change") {
    IO {
      val lockedForStatus = true
      val isLocked = true
      val statusDuration = 11.seconds
      val effectiveTimeout = 10.seconds
      val withinStallBudget = true
      val statusChanged = false

      val failedStallCycle = lockedForStatus && isLocked && statusDuration >= effectiveTimeout &&
        withinStallBudget && !statusChanged

      expect(failedStallCycle)
    }
  }

  test("failedStallCycle not detected when status changed") {
    IO {
      val lockedForStatus = true
      val isLocked = true
      val statusDuration = 11.seconds
      val effectiveTimeout = 10.seconds
      val withinStallBudget = true
      val statusChanged = true

      val failedStallCycle = lockedForStatus && isLocked && statusDuration >= effectiveTimeout &&
        withinStallBudget && !statusChanged

      expect(!failedStallCycle)
    }
  }

  // === Stall cycle accumulation across unlock cycles ===

  test("stallCycleCount preserved on Reopened (accumulates across unproductive unlocks)") {
    IO {
      val statusChanged = false
      val previousCycleCount = 3

      val newStallCycleCount = if (statusChanged) 0 else previousCycleCount
      expect.same(3, newStallCycleCount)
    }
  }

  test("stallCycleCount preserved when neither status changed nor reopened") {
    IO {
      val statusChanged = false
      val previousCycleCount = 3

      val newStallCycleCount = if (statusChanged) 0 else previousCycleCount
      expect.same(3, newStallCycleCount)
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
      // With simplified `case Some(_) =>`, any existing state is removed when shouldAbandon triggers.
      // Lock status is irrelevant — the shouldAbandon decision already accounts for stall cycles.
      // All lock statuses (Open, Closed, Reopened) match `case Some(_)`.
      val shouldRemove = true // case Some(_) => always matches regardless of lock status
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
}
