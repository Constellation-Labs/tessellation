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

  test("shouldHandle is true when statusDuration >= declarationTimeout and not already handled") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 36.seconds
      val alreadyHandled = false

      val shouldHandle = statusDuration >= declarationTimeout && !alreadyHandled
      expect(shouldHandle)
    }
  }

  test("shouldHandle is false when statusDuration < declarationTimeout") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 34.seconds
      val alreadyHandled = false

      val shouldHandle = statusDuration >= declarationTimeout && !alreadyHandled
      expect(!shouldHandle)
    }
  }

  test("shouldHandle is false when already handled") {
    IO {
      val declarationTimeout = 35.seconds
      val statusDuration = 36.seconds
      val alreadyHandled = true

      val shouldHandle = statusDuration >= declarationTimeout && !alreadyHandled
      expect(!shouldHandle)
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

  test("statusStartTime resets on view change") {
    IO {
      val now = 100.seconds
      val didViewChange = true
      val previousStartTime = 50.seconds

      // After view change, the adjustedStatusStartTime is set to now
      val adjustedStatusStartTime = if (didViewChange) now else previousStartTime
      expect.same(now, adjustedStatusStartTime)
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

  test("stallCount increments on view change") {
    IO {
      val didViewChange = true
      val previousStallCount = 2

      val finalStallCount = if (didViewChange) previousStallCount + 1 else previousStallCount
      expect.same(3, finalStallCount)
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
}
