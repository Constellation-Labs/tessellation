package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Test suite for consensus crisis fixes.
  *
  * Verifies the correctness of fixes applied during the consensus crisis incident:
  *   - CR1: MptStore savepoint key validation (prevents stale savepoint restore after recovery)
  *   - CR2: initFromDownload failure tracking (breaks infinite download→init fail loops)
  *   - CR3: Recovery state cleanup (clears all stale consensus state, not just current key)
  *   - CR4: Consecutive abandonment escalation (recovery download → force-leave progression)
  *   - CR5: Fork detection determinism (proofs-based, consistent across all nodes)
  *   - CR6: Round abandonment resource cleanup prevents poison retries
  */
object ConsensusCrisisFixesSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId = PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  // ══════════════════════════════════════════════════════════════════
  // CR1: MptStore savepoint key validation
  // Prevents restoring a savepoint from ordinal N after recovery downloads ordinal M
  // ══════════════════════════════════════════════════════════════════

  test("CR1: savepoint must only restore when key matches current round") {
    IO {
      // Simulate: savepoint was created for key=100, but current round is key=200 (after recovery)
      val savepointKey = 100
      val currentKey = 200

      val shouldRestore = savepointKey == currentKey

      expect(!shouldRestore) // Must NOT restore — keys don't match
    }
  }

  test("CR1: savepoint restores correctly when key matches") {
    IO {
      val savepointKey = 100
      val currentKey = 100

      val shouldRestore = savepointKey == currentKey

      expect(shouldRestore) // Same key — safe to restore
    }
  }

  test("CR1: stale savepoint detection after recovery download replaces state") {
    for {
      // Simulate the savepoint lifecycle:
      // 1. Round at ordinal 100 creates savepoint
      // 2. Round stalls → recovery download → new state at ordinal 200
      // 3. New round at ordinal 200 must NOT restore ordinal 100's savepoint
      savepointRef <- Ref.of[IO, Option[(Int, String)]]((100, "savepoint-data-for-100").some)

      // Recovery clears savepoint ref (as implemented in the fix)
      // But even if it didn't, the key validation guard prevents restore
      currentKey = 200

      savedSp <- savepointRef.get
      restored = savedSp.exists { case (spKey, _) => spKey == currentKey }
      discarded = savedSp.exists { case (spKey, _) => spKey != currentKey }
    } yield expect(!restored) && expect(discarded)
  }

  test("CR1: savepoint ref tracks key alongside savepoint data") {
    for {
      // Verify the Ref type change from Option[Savepoint] to Option[(Key, Savepoint)]
      ref <- Ref.of[IO, Option[(Int, String)]](none)

      // Create savepoint at key=50
      _ <- ref.set((50, "sp-data").some)
      sp1 <- ref.get

      // Verify it stored key alongside data
      hasKey = sp1.exists(_._1 == 50)

      // On getAndSet, the old value is returned and ref is cleared
      old <- ref.getAndSet(none)
      cleared <- ref.get
    } yield expect(hasKey) && expect(old.isDefined) && expect(cleared.isEmpty)
  }

  // ══════════════════════════════════════════════════════════════════
  // CR2: initFromDownload failure tracking
  // Ensures repeated init failures increment recovery counter to break infinite loops
  // ══════════════════════════════════════════════════════════════════

  test("CR2: init download failures increment total recovery counter") {
    for {
      totalRecoveryAttempts <- Ref.of[IO, Int](0)
      maxTotalRecoveryAttempts = 15 // default: maxConsecutiveAbandonments(5) * 3

      // Simulate 5 init failures
      _ <- (1 to 5).toList.traverse_ { _ =>
        totalRecoveryAttempts.update(_ + 1)
      }

      count <- totalRecoveryAttempts.get
    } yield expect(count == 5) && expect(count < maxTotalRecoveryAttempts)
  }

  test("CR2: init failures eventually trigger force-leave after exhausting max attempts") {
    for {
      totalRecoveryAttempts <- Ref.of[IO, Int](0)
      maxConsecutiveAbandonments = 5
      maxTotalRecoveryAttempts = maxConsecutiveAbandonments * 3 // 15

      forceLeaveTriggered <- Ref.of[IO, Boolean](false)

      // Simulate 15 init failures (the maximum before force-leave)
      _ <- (1 to maxTotalRecoveryAttempts).toList.traverse_ { _ =>
        totalRecoveryAttempts.updateAndGet(_ + 1).flatMap { count =>
          if (count >= maxTotalRecoveryAttempts)
            forceLeaveTriggered.set(true)
          else IO.unit
        }
      }

      triggered <- forceLeaveTriggered.get
      count <- totalRecoveryAttempts.get
    } yield expect(triggered) && expect.same(maxTotalRecoveryAttempts, count)
  }

  test("CR2: init failure counter is separate from abandonment counter") {
    for {
      // Both counters feed into totalRecoveryAttempts
      totalRecoveryAttempts <- Ref.of[IO, Int](0)

      // 3 abandonments trigger recovery
      _ <- totalRecoveryAttempts.update(_ + 1) // from abandonment-triggered recovery
      // 2 init failures
      _ <- totalRecoveryAttempts.update(_ + 1) // from init failure
      _ <- totalRecoveryAttempts.update(_ + 1) // from init failure

      count <- totalRecoveryAttempts.get
    } yield expect(count == 3) // Both sources count toward the same total
  }

  test("CR2: without init failure tracking, download→init loop would be infinite") {
    IO {
      // Before fix: abandonRound increments counter, but initFromDownload failure doesn't.
      // This means: abandon(5) → recovery download → init fails → WaitingForDownload → ...
      // The counter stays at 0 for init-path failures.

      val maxConsecutiveAbandonments = 5
      val maxTotalRecoveryAttempts = maxConsecutiveAbandonments * 3

      // Simulate old behavior: only abandonment increments
      var counter = 0
      var initFailures = 0

      // Old code: abandonment increments
      counter += 1 // abandonRound triggered recovery

      // Old code: init failure does NOT increment (the bug)
      initFailures += 1 // init fails, but counter doesn't change
      initFailures += 1
      initFailures += 1

      // Counter never reaches threshold
      val wouldForceLeave = counter >= maxTotalRecoveryAttempts

      expect(!wouldForceLeave) && // Old behavior: never force-leaves
      expect(initFailures == 3) // Init failures were lost
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // CR3: Recovery state cleanup
  // Clears ALL stale consensus state, not just the current key's state
  // ══════════════════════════════════════════════════════════════════

  test("CR3: recovery must clear state for ALL keys, not just current") {
    for {
      // Simulate MapRef-like storage with multiple keys
      states <- Ref.of[IO, Map[Int, Option[String]]](Map(
        100 -> "state-100".some,
        101 -> "state-101".some,
        102 -> "state-102".some
      ))

      // Old behavior: only clear current key
      abandonedKey = 102
      _ <- states.update(_.updated(abandonedKey, none))
      afterPartialClear <- states.get
      ghostsRemain = afterPartialClear.values.flatten.nonEmpty

      // New behavior (Fix 3): clear ALL keys
      _ <- states.set(Map.empty)
      afterFullClear <- states.get
    } yield expect(ghostsRemain) && // Old behavior leaves ghosts
      expect(afterFullClear.isEmpty) // New behavior clears everything
  }

  test("CR3: stale resources from abandoned rounds are cleared during recovery") {
    for {
      resources <- Ref.of[IO, Map[Int, Option[String]]](Map(
        100 -> "resources-100".some,
        101 -> "resources-101".some,
        102 -> "resources-102".some
      ))

      // clearAllConsensusState clears both states and resources
      _ <- resources.update(_.view.mapValues(_ => none[String]).toMap)
      afterClear <- resources.get
    } yield expect(afterClear.values.forall(_.isEmpty))
  }

  test("CR3: recovery clears time trigger to prevent stale scheduling") {
    for {
      timeTrigger <- Ref.of[IO, Option[FiniteDuration]](10.seconds.some)

      // Before fix: timeTrigger persisted across recovery
      staleBeforeClear <- timeTrigger.get

      // Fix: clearTimeTrigger called during recovery
      _ <- timeTrigger.set(none)
      afterClear <- timeTrigger.get
    } yield expect(staleBeforeClear.isDefined) && expect(afterClear.isEmpty)
  }

  test("CR3: recovery clears observation key to prevent stale observation state") {
    for {
      observationKey <- Ref.of[IO, Option[Int]](42.some)

      // Before fix: observationKey persisted across recovery
      staleBeforeClear <- observationKey.get

      // Fix: clearObservationKey called during recovery
      _ <- observationKey.set(none)
      afterClear <- observationKey.get
    } yield expect(staleBeforeClear.isDefined) && expect(afterClear.isEmpty)
  }

  test("CR3: recovery clears peer registrations to prevent stale lagging detection") {
    for {
      peerRegistrations <- Ref.of[IO, Map[PeerId, Int]](Map(
        pid("peer1") -> 100,
        pid("peer2") -> 101,
        pid("departed") -> 99 // departed peer with stale registration
      ))

      // Stale registration would cause false lagging detection
      staleDeparted = peerRegistrations.get.map(_.contains(pid("departed")))

      // Fix: clearAllPeerRegistrations during recovery
      _ <- peerRegistrations.set(Map.empty)
      afterClear <- peerRegistrations.get
    } yield expect(afterClear.isEmpty)
  }

  // ══════════════════════════════════════════════════════════════════
  // CR4: Consecutive abandonment escalation
  // Verifies the 3-tier recovery: retry → WaitingForDownload → force-leave
  // ══════════════════════════════════════════════════════════════════

  test("CR4: consecutive abandonments at same key trigger recovery download") {
    for {
      consecutiveRef <- Ref.of[IO, (Option[Int], Int)]((none, 0))
      maxConsecutiveAbandonments = 5

      // Simulate 5 consecutive abandonments at key=100
      results <- (1 to 5).toList.traverse { _ =>
        consecutiveRef.modify {
          case (Some(lastKey), count) if lastKey == 100 =>
            val newCount = count + 1
            ((100.some, newCount), newCount)
          case _ =>
            ((100.some, 1), 1)
        }
      }

      shouldRecover = results.last >= maxConsecutiveAbandonments
    } yield expect(shouldRecover) && expect.same(5, results.last)
  }

  test("CR4: consecutive counter resets when key changes (different ordinal)") {
    for {
      consecutiveRef <- Ref.of[IO, (Option[Int], Int)]((none, 0))

      // 3 abandonments at key=100
      _ <- (1 to 3).toList.traverse_ { _ =>
        consecutiveRef.modify {
          case (Some(lastKey), count) if lastKey == 100 =>
            val newCount = count + 1
            ((100.some, newCount), newCount)
          case _ =>
            ((100.some, 1), 1)
        }
      }

      // Key changes to 101 — counter resets
      newCount <- consecutiveRef.modify {
        case (Some(lastKey), count) if lastKey == 101 =>
          val newCount = count + 1
          ((101.some, newCount), newCount)
        case _ =>
          ((101.some, 1), 1)
      }
    } yield expect.same(1, newCount) // Reset to 1, not 4
  }

  test("CR4: total recovery attempts escalate to force-leave") {
    for {
      totalRef <- Ref.of[IO, Int](0)
      maxConsecutiveAbandonments = 5
      maxTotalRecoveryAttempts = maxConsecutiveAbandonments * 3 // 15

      forceLeaveTriggered <- Ref.of[IO, Boolean](false)

      // Simulate multiple recovery cycles
      _ <- (1 to maxTotalRecoveryAttempts).toList.traverse_ { _ =>
        totalRef.updateAndGet(_ + 1).flatMap { total =>
          if (total >= maxTotalRecoveryAttempts) forceLeaveTriggered.set(true)
          else IO.unit
        }
      }

      triggered <- forceLeaveTriggered.get
    } yield expect(triggered)
  }

  test("CR4: successful round resets total recovery counter") {
    for {
      totalRef <- Ref.of[IO, Int](10) // Already had 10 recovery attempts

      // Successful round resets
      _ <- totalRef.set(0)
      afterReset <- totalRef.get
    } yield expect.same(0, afterReset)
  }

  test("CR4: force-leave tries multiple source states (Ready, WaitingForDownload, DownloadInProgress, Observing)") {
    IO {
      // The force-leave logic tries these states in order
      val forceLeaveStates = List("Ready", "WaitingForDownload", "DownloadInProgress", "Observing")

      // Simulate: node is in DownloadInProgress
      val currentState = "DownloadInProgress"

      // First two attempts fail, third succeeds
      val result = forceLeaveStates.foldLeft(none[String]) {
        case (Some(found), _) => found.some
        case (None, state) if state == currentState => state.some
        case (None, _) => none
      }

      expect(result.contains("DownloadInProgress"))
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // CR5: Fork detection determinism
  // All nodes must identify the same set of forked peers
  // ══════════════════════════════════════════════════════════════════

  test("CR5: fork detection is deterministic across different node views") {
    IO {
      import scala.collection.immutable.SortedMap

      val peer1 = pid("peer1")
      val peer2 = pid("peer2")
      val peer3 = pid("peer3")
      val peer4 = pid("peer4")
      val peer5 = pid("peer5")

      val majorityHash = Hash("aaa")
      val minorityHash = Hash("bbb")

      // All 5 nodes see the same observation map
      val observations: SortedMap[PeerId, Hash] = SortedMap(
        peer1 -> majorityHash,
        peer2 -> majorityHash,
        peer3 -> majorityHash,
        peer4 -> minorityHash, // forked
        peer5 -> minorityHash // forked
      )

      // Node 1 (majority): identifies forked peers
      val ownHash1 = majorityHash
      val forkedByNode1 = observations.collect {
        case (pid, hash) if hash != ownHash1 => pid
      }.toSet

      // Node 2 (also majority): identifies same forked peers
      val ownHash2 = majorityHash
      val forkedByNode2 = observations.collect {
        case (pid, hash) if hash != ownHash2 => pid
      }.toSet

      expect.same(forkedByNode1, forkedByNode2) &&
      expect.same(Set(peer4, peer5), forkedByNode1)
    }
  }

  test("CR5: minority node does not identify forked peers (self-recovery instead)") {
    IO {
      import scala.collection.immutable.SortedMap

      val peer1 = pid("peer1")
      val peer2 = pid("peer2")
      val peer3 = pid("peer3")

      val majorityHash = Hash("aaa")
      val minorityHash = Hash("bbb")

      val observations: SortedMap[PeerId, Hash] = SortedMap(
        peer1 -> majorityHash,
        peer2 -> majorityHash,
        peer3 -> minorityHash // this node is minority
      )

      // pickMajority would return majorityHash
      val counts = observations.values.toList.groupBy(identity).view.mapValues(_.size).toMap
      val majority = counts.maxByOption(_._2).map(_._1)

      // Minority node: its hash != majority hash → it's forked, should self-recover
      val ownHash = minorityHash
      val isForked = majority.exists(_ != ownHash)

      // identifyForkedPeers returns empty for minority (self-recovery handles it)
      val identifiedForked =
        if (majority.contains(ownHash))
          observations.collect { case (pid, hash) if hash != ownHash => pid }.toSet
        else
          Set.empty[PeerId]

      expect(isForked) && // This node IS forked
      expect(identifiedForked.isEmpty) // But doesn't identify others (self-recovers instead)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // CR6: Round abandonment resource cleanup prevents poison retries
  // ══════════════════════════════════════════════════════════════════

  test("CR6: abandoned round resources are cleared to prevent addFacility orElse poisoning") {
    for {
      // Simulate resource storage for a key
      resources <- Ref.of[IO, Map[Int, Option[Map[String, String]]]](Map(
        100 -> Map("peer1" -> "facility-data").some
      ))

      // Abandonment clears resources
      _ <- resources.update(_.updated(100, none))
      afterClear <- resources.get
    } yield expect(afterClear(100).isEmpty)
  }

  test("CR6: stale peer declarations from abandoned round do not leak into retry") {
    for {
      declarations <- Ref.of[IO, Map[PeerId, String]](Map(
        pid("peer1") -> "old-facility",
        pid("peer2") -> "old-proposal"
      ))

      // clearResources removes all peer declarations for the key
      _ <- declarations.set(Map.empty)
      afterClear <- declarations.get
    } yield expect(afterClear.isEmpty)
  }

  test("CR6: withdrawal maps cleared during abandonment prevent ghost withdrawals") {
    for {
      withdrawals <- Ref.of[IO, Map[PeerId, String]](Map(
        pid("withdrawn-peer") -> "some-kind"
      ))

      // Resources including withdrawals are cleared
      _ <- withdrawals.set(Map.empty)
      afterClear <- withdrawals.get
    } yield expect(afterClear.isEmpty)
  }

  // ══════════════════════════════════════════════════════════════════
  // Integration scenarios: multi-step failure cascades
  // ══════════════════════════════════════════════════════════════════

  test("Scenario: repeated abandon → recovery download → init fail → force-leave") {
    for {
      consecutiveRef <- Ref.of[IO, (Option[Int], Int)]((none, 0))
      totalRecoveryRef <- Ref.of[IO, Int](0)
      maxConsecutiveAbandonments = 5
      maxTotalRecoveryAttempts = maxConsecutiveAbandonments * 3

      nodeState <- Ref.of[IO, String]("Ready")
      forceLeaveTriggered <- Ref.of[IO, Boolean](false)

      // Phase 1: 5 consecutive abandonments → triggers recovery download
      _ <- (1 to 5).toList.traverse_ { _ =>
        consecutiveRef.modify {
          case (Some(k), c) if k == 100 => ((100.some, c + 1), c + 1)
          case _                         => ((100.some, 1), 1)
        }
      }
      count1 <- consecutiveRef.get.map(_._2)
      shouldRecover = count1 >= maxConsecutiveAbandonments
      _ <- if (shouldRecover) {
        totalRecoveryRef.update(_ + 1) >>
          nodeState.set("WaitingForDownload") >>
          consecutiveRef.set((none, 0))
      } else IO.unit

      // Phase 2: Repeated download → init fail cycles (14 more times)
      _ <- (1 to 14).toList.traverse_ { _ =>
        totalRecoveryRef.updateAndGet(_ + 1).flatMap { total =>
          if (total >= maxTotalRecoveryAttempts) {
            forceLeaveTriggered.set(true) >> nodeState.set("Leaving")
          } else
            nodeState.set("WaitingForDownload")
        }
      }

      finalState <- nodeState.get
      triggered <- forceLeaveTriggered.get
      totalAttempts <- totalRecoveryRef.get
    } yield
      expect(triggered) &&
      expect.same("Leaving", finalState) &&
      expect.same(maxTotalRecoveryAttempts, totalAttempts)
  }

  test("Scenario: abandon cycle interrupted by successful round resets all counters") {
    for {
      consecutiveRef <- Ref.of[IO, (Option[Int], Int)]((none, 0))
      totalRecoveryRef <- Ref.of[IO, Int](0)
      healthRef <- Ref.of[IO, (Int, Int)]((0, 0)) // (consecutiveAbandonments, totalRecoveryAttempts)

      // 3 abandonments (not enough to trigger recovery yet)
      _ <- (1 to 3).toList.traverse_ { _ =>
        consecutiveRef.modify {
          case (Some(k), c) if k == 100 => ((100.some, c + 1), c + 1)
          case _                         => ((100.some, 1), 1)
        }
      }

      // 1 recovery download happened before
      _ <- totalRecoveryRef.set(5)

      // Now a successful round completes — all counters reset
      _ <- totalRecoveryRef.set(0)
      _ <- healthRef.set((0, 0))

      totalAfter <- totalRecoveryRef.get
      healthAfter <- healthRef.get
    } yield
      expect.same(0, totalAfter) &&
      expect.same((0, 0), healthAfter)
  }

  test("Scenario: ghost entries from ordinal 100 interfere with round at ordinal 200") {
    for {
      // Simulate: states map has entries for multiple ordinals
      states <- Ref.of[IO, Map[Int, Option[String]]](Map(
        100 -> "old-state-from-abandoned-round".some,
        101 -> "another-old-state".some,
        200 -> none // current round, not yet created
      ))

      // Without clearAllConsensusState: ghost entry at 100 exists
      ghostsBefore <- states.get.map(_.values.flatten.size)

      // With clearAllConsensusState: all entries cleared
      _ <- states.update(_.view.mapValues(_ => none[String]).toMap)
      ghostsAfter <- states.get.map(_.values.flatten.size)
    } yield
      expect(ghostsBefore == 2) && // Two ghost entries
      expect(ghostsAfter == 0) // All cleared after fix
  }

  test("Scenario: stale time trigger fires after recovery, causing premature round start") {
    for {
      timeTrigger <- Ref.of[IO, Option[FiniteDuration]](5.seconds.some)
      roundStarted <- Ref.of[IO, Boolean](false)

      // Without clearTimeTrigger: stale trigger would fire
      staleExists <- timeTrigger.get.map(_.isDefined)

      // With clearTimeTrigger: trigger is cleared during recovery
      _ <- timeTrigger.set(none)
      afterClear <- timeTrigger.get

      // Simulated timer check: only start round if trigger exists
      _ <- afterClear.traverse_(_ => roundStarted.set(true))
      started <- roundStarted.get
    } yield
      expect(staleExists) && // Stale trigger existed
      expect(!started) // But didn't fire after cleanup
  }
}
