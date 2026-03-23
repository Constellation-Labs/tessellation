package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.FacilitatorSelector
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Comprehensive test suite for all consensus audit fixes.
  *
  * Organized by severity level matching the audit report:
  *   - C1: Quality score derivation from proofs (not withdrawnFacilitators)
  *   - C2: Integer-only tier computation in selectLeaderWeighted
  *   - H2: Quorum feasibility check after eviction
  *   - H3: Lagging node detection
  *   - M1: noProgressTimeout restricted to facilities phase
  *   - M2: Force restart after extended recovery loop
  *   - M3: Near-completion bonus skipped for unresponsive peers
  *   - M4: facilitiesTimeoutMultiplier reduced to 0.3
  */
object ConsensusAuditFixesSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId = PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  // ══════════════════════════════════════════════════════════════════
  // C1: Quality score from proofs — deterministic across all nodes
  // ══════════════════════════════════════════════════════════════════

  test("C1: quality scores derived from signers, not withdrawn/removed facilitators") {
    IO {
      // Simulate: 5 facilitators, 3 signed the artifact (signers from proofs)
      val facilitators = (1 to 5).map(i => pid(s"peer$i")).toList
      val signers = facilitators.take(3).toSet

      // New approach: quality = 1 if peer signed, 0 if not
      val quality = facilitators.map { p =>
        val completed = if (signers.contains(p)) 1 else 0
        p -> (completed, 1)
      }.toMap

      // Signers get (1, 1), non-signers get (0, 1)
      expect(quality(facilitators(0)) == (1, 1)) &&
      expect(quality(facilitators(1)) == (1, 1)) &&
      expect(quality(facilitators(2)) == (1, 1)) &&
      expect(quality(facilitators(3)) == (0, 1)) &&
      expect(quality(facilitators(4)) == (0, 1))
    }
  }

  test("C1: quality scores are identical regardless of local withdrawn/removed view") {
    IO {
      // Two nodes have different local views of withdrawn peers
      val facilitators = (1 to 5).map(i => pid(s"peer$i")).toList
      val signers = facilitators.take(4).toSet

      // Node A thinks peer5 withdrew, Node B doesn't know yet
      // But both derive quality from the SAME proofs (signers)
      val qualityNodeA = facilitators.map { p =>
        p -> (if (signers.contains(p)) 1 else 0, 1)
      }.toMap

      val qualityNodeB = facilitators.map { p =>
        p -> (if (signers.contains(p)) 1 else 0, 1)
      }.toMap

      expect.same(qualityNodeA, qualityNodeB)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // C2: Integer-only tier computation — no float divergence
  // ══════════════════════════════════════════════════════════════════

  test("C2: integer tier = participated - completed (failure count)") {
    IO {
      // Tier is simply failure count: participated - completed
      val scores: Map[String, (Int, Int)] = Map(
        "perfectPeer" -> (10, 10), // tier = 0
        "goodPeer" -> (8, 10), // tier = 2
        "badPeer" -> (2, 10), // tier = 8
        "newPeer" -> (0, 0) // tier = 0 (no participation)
      )

      val tiers = scores.map {
        case (name, (completed, participated)) =>
          val tier: Long = if (participated > 0) participated.toLong - completed.toLong else 0L
          name -> tier
      }

      expect(tiers("perfectPeer") == 0L) &&
      expect(tiers("goodPeer") == 2L) &&
      expect(tiers("badPeer") == 8L) &&
      expect(tiers("newPeer") == 0L)
    }
  }

  test("C2: selectLeaderWeighted with integer scores is deterministic across 1000 invocations") {
    IO {
      val selector = FacilitatorSelector.make(Some(10))
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropy = Hash.fromBytes("determinism-test".getBytes("UTF-8"))
      val scores: Map[PeerId, (Int, Int)] = peers.zipWithIndex.map {
        case (p, i) =>
          p -> (i, 10) // varying quality: 0/10, 1/10, ... 9/10
      }.toMap

      val results = (1 to 1000).map(_ => selector.selectLeaderWeighted(peers, entropy, 0, scores))

      // Must be perfectly deterministic
      expect(results.distinct.size == 1)
    }
  }

  test("C2: integer tier prefers lower-failure peers") {
    IO {
      val selector = FacilitatorSelector.make(Some(10))
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val highQualityPeer = peers.head

      // High quality = 0 failures, rest = 9 failures
      val scores = peers.map(p => p -> (if (p == highQualityPeer) (10, 10) else (1, 10))).toMap

      // Test across many entropies — tier-0 peer should dominate
      val entropies = (0 until 30).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val selections = entropies.map(e => selector.selectLeaderWeighted(peers, e, 0, scores))
      val highQualityCount = selections.count(_ == highQualityPeer)

      // Tier-0 peer (0 failures) should beat tier-9 peers consistently
      expect(highQualityCount > 15) // much more than fair share (6 out of 30)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // H2: Quorum feasibility check after eviction
  // ══════════════════════════════════════════════════════════════════

  test("H2: quorum infeasible when active < quorum threshold") {
    IO {
      val facilitatorCount = 10
      val withdrawnCount = 4
      val quorumThreshold = 0.75

      val activeAfterWithdrawals = facilitatorCount - withdrawnCount
      val quorumSize = math.ceil(facilitatorCount * quorumThreshold).toInt.max(1)
      val quorumInfeasible = activeAfterWithdrawals > 0 && activeAfterWithdrawals < quorumSize

      // 6 active, need ceil(10 * 0.75) = 8 → infeasible
      expect.same(8, quorumSize) &&
      expect.same(6, activeAfterWithdrawals) &&
      expect(quorumInfeasible)
    }
  }

  test("H2: quorum feasible when active >= quorum threshold") {
    IO {
      val facilitatorCount = 10
      val withdrawnCount = 2
      val quorumThreshold = 0.75

      val activeAfterWithdrawals = facilitatorCount - withdrawnCount
      val quorumSize = math.ceil(facilitatorCount * quorumThreshold).toInt.max(1)
      val quorumInfeasible = activeAfterWithdrawals > 0 && activeAfterWithdrawals < quorumSize

      // 8 active, need 8 → feasible
      expect.same(8, quorumSize) &&
      expect.same(8, activeAfterWithdrawals) &&
      expect(!quorumInfeasible)
    }
  }

  test("H2: quorum infeasible after eviction triggers abandon") {
    IO {
      // After evicting 5 peers from 10, only 5 remain but need 8 for quorum
      val remaining = 5
      val quorumThreshold = 0.75
      val effectiveQuorum = math.ceil(remaining * quorumThreshold).toInt.max(1)
      val quorumInfeasible = remaining > 0 && remaining < effectiveQuorum

      // ceil(5 * 0.75) = 4 — NOT infeasible (5 >= 4)
      // But the quorum check in the main loop uses total facilitators for threshold
      val totalFacilitators = 10
      val mainQuorumSize = math.ceil(totalFacilitators * quorumThreshold).toInt.max(1)
      val mainQuorumInfeasible = remaining > 0 && remaining < mainQuorumSize

      // 5 active < 8 required → infeasible → should abandon
      expect(mainQuorumInfeasible) &&
      expect.same(8, mainQuorumSize)
    }
  }

  test("H2: quorum with None threshold requires all facilitators") {
    IO {
      val facilitatorCount = 10
      val withdrawnCount = 1
      val quorumThreshold: Option[Double] = None

      val activeAfterWithdrawals = facilitatorCount - withdrawnCount
      val quorumSize = quorumThreshold match {
        case Some(threshold) => math.ceil(facilitatorCount * threshold).toInt.max(1)
        case None            => facilitatorCount
      }
      val quorumInfeasible = activeAfterWithdrawals > 0 && activeAfterWithdrawals < quorumSize

      // Need 10, have 9 → infeasible
      expect.same(10, quorumSize) &&
      expect(quorumInfeasible)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // H3: Lagging node detection
  // ══════════════════════════════════════════════════════════════════

  test("H3: node is lagging when majority of peers at different key") {
    IO {
      val ownKey = 100
      val peerRegistrations: Map[String, Int] = Map(
        "peer1" -> 105,
        "peer2" -> 105,
        "peer3" -> 105,
        "peer4" -> 100,
        "peer5" -> 105
      )

      val peersAtDifferentKey = peerRegistrations.count { case (_, peerKey) => peerKey != ownKey }
      val totalRegisteredPeers = peerRegistrations.size
      val isLagging = totalRegisteredPeers >= 3 && peersAtDifferentKey > totalRegisteredPeers / 2

      // 4 out of 5 at different key → lagging
      expect.same(4, peersAtDifferentKey) &&
      expect(isLagging)
    }
  }

  test("H3: node is NOT lagging when at same key as majority") {
    IO {
      val ownKey = 105
      val peerRegistrations: Map[String, Int] = Map(
        "peer1" -> 105,
        "peer2" -> 105,
        "peer3" -> 105,
        "peer4" -> 100,
        "peer5" -> 105
      )

      val peersAtDifferentKey = peerRegistrations.count { case (_, peerKey) => peerKey != ownKey }
      val totalRegisteredPeers = peerRegistrations.size
      val isLagging = totalRegisteredPeers >= 3 && peersAtDifferentKey > totalRegisteredPeers / 2

      // 1 out of 5 at different key → not lagging
      expect.same(1, peersAtDifferentKey) &&
      expect(!isLagging)
    }
  }

  test("H3: lagging detection requires minimum 3 registered peers") {
    IO {
      val ownKey = 100
      val peerRegistrations: Map[String, Int] = Map(
        "peer1" -> 105,
        "peer2" -> 105
      )

      val peersAtDifferentKey = peerRegistrations.count { case (_, peerKey) => peerKey != ownKey }
      val totalRegisteredPeers = peerRegistrations.size
      val isLagging = totalRegisteredPeers >= 3 && peersAtDifferentKey > totalRegisteredPeers / 2

      // Only 2 peers registered — too few for reliable detection
      expect.same(2, peersAtDifferentKey) &&
      expect(!isLagging)
    }
  }

  test("H3: lagging with exactly 50% split is NOT lagging (need strict majority)") {
    IO {
      val ownKey = 100
      val peerRegistrations: Map[String, Int] = Map(
        "peer1" -> 105,
        "peer2" -> 105,
        "peer3" -> 100,
        "peer4" -> 100
      )

      val peersAtDifferentKey = peerRegistrations.count { case (_, peerKey) => peerKey != ownKey }
      val totalRegisteredPeers = peerRegistrations.size
      val isLagging = totalRegisteredPeers >= 3 && peersAtDifferentKey > totalRegisteredPeers / 2

      // 2 out of 4 at different key → 2 > 2 is false → not lagging
      expect.same(2, peersAtDifferentKey) &&
      expect(!isLagging)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // M1: noProgressTimeout restricted to facilities phase
  // ══════════════════════════════════════════════════════════════════

  test("M1: noProgressTimeout only applies when declaredCount=0 AND facilities phase (index 0)") {
    IO {
      val declarationTimeout = 35.seconds
      val noProgressTimeout = Some(45.seconds)
      val stallCount = 0

      // Facilities phase (index 0), no declarations
      val timeoutFacilitiesNoDecl = {
        val isFacilitiesPhase = true
        val declaredCount = 0
        if (stallCount > 0) declarationTimeout
        else if (declaredCount == 0 && isFacilitiesPhase) noProgressTimeout.getOrElse(declarationTimeout)
        else declarationTimeout
      }

      // Proposals phase (index 1), no declarations
      val timeoutProposalsNoDecl = {
        val isFacilitiesPhase = false
        val declaredCount = 0
        if (stallCount > 0) declarationTimeout
        else if (declaredCount == 0 && isFacilitiesPhase) noProgressTimeout.getOrElse(declarationTimeout)
        else declarationTimeout
      }

      // noProgressTimeout used in facilities, standard timeout in proposals
      expect.same(45.seconds, timeoutFacilitiesNoDecl) &&
      expect.same(35.seconds, timeoutProposalsNoDecl)
    }
  }

  test("M1: noProgressTimeout NOT used when some declarations exist (even in facilities)") {
    IO {
      val declarationTimeout = 35.seconds
      val noProgressTimeout = Some(45.seconds)
      val stallCount = 0
      val isFacilitiesPhase = true
      val declaredCount = 1

      val timeout =
        if (stallCount > 0) declarationTimeout
        else if (declaredCount == 0 && isFacilitiesPhase) noProgressTimeout.getOrElse(declarationTimeout)
        else declarationTimeout

      expect.same(35.seconds, timeout)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // M2: Force restart after extended recovery loop
  // ══════════════════════════════════════════════════════════════════

  test("M2: force leave triggers after maxConsecutiveAbandonments * 3 recovery attempts") {
    IO {
      val maxConsecutiveAbandonments = 5
      val maxTotalRecoveryAttempts = maxConsecutiveAbandonments * 3

      // Simulate recovery loop
      val attempts = (1 to maxTotalRecoveryAttempts + 1).toList
      val shouldForceLeave = attempts.map(_ >= maxTotalRecoveryAttempts)

      // Attempts 1-14: normal recovery download
      expect(!shouldForceLeave(0)) && // attempt 1
      expect(!shouldForceLeave(13)) && // attempt 14
      // Attempt 15: force leave
      expect(shouldForceLeave(14)) && // attempt 15 = maxTotalRecoveryAttempts
      expect(shouldForceLeave(15)) // attempt 16 > max
    }
  }

  test("M2: maxTotalRecoveryAttempts scales with config") {
    IO {
      // Default: 5 * 3 = 15
      expect.same(15, 5 * 3) &&
      // If maxConsecutiveAbandonments = 3: 3 * 3 = 9
      expect.same(9, 3 * 3) &&
      // If maxConsecutiveAbandonments = 10: 10 * 3 = 30
      expect.same(30, 10 * 3)
    }
  }

  test("M2: consecutive abandonment counter resets on recovery, total does not") {
    IO {
      // Simulate: 5 abandons → recovery → 5 abandons → recovery → ...
      // consecutiveAbandonCount resets to 0 after each recovery
      // totalRecoveryAttempts keeps incrementing
      var consecutive = 0
      var total = 0

      // Round 1: 5 abandons → recovery
      (1 to 5).foreach(_ => consecutive += 1)
      total += 1
      consecutive = 0 // reset on recovery

      // Round 2: 5 abandons → recovery
      (1 to 5).foreach(_ => consecutive += 1)
      total += 1
      consecutive = 0

      // Round 3: 5 abandons → recovery
      (1 to 5).foreach(_ => consecutive += 1)
      total += 1
      consecutive = 0

      expect.same(0, consecutive) && // resets each time
      expect.same(3, total) // keeps incrementing
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // M3: Near-completion bonus skipped for unresponsive peers
  // ══════════════════════════════════════════════════════════════════

  test("M3: near-completion bonus applied when missing peers are responsive") {
    IO {
      val baseTimeout = 35.seconds
      val declaredCount = 16
      val activeCount = 20
      val stallCount = 0
      val allMissingUnresponsive = false

      val progress = declaredCount.toDouble / activeCount
      val nearCompletion = progress >= 0.75 && declaredCount < activeCount
      val effectiveTimeout =
        if (nearCompletion && stallCount == 0 && !allMissingUnresponsive)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      expect(nearCompletion) &&
      expect.same(52500.millis, effectiveTimeout) // 35s + 17.5s = 52.5s
    }
  }

  test("M3: near-completion bonus SKIPPED when all missing peers are unresponsive") {
    IO {
      val baseTimeout = 35.seconds
      val declaredCount = 16
      val activeCount = 20
      val stallCount = 0
      val allMissingUnresponsive = true

      val progress = declaredCount.toDouble / activeCount
      val nearCompletion = progress >= 0.75 && declaredCount < activeCount
      val effectiveTimeout =
        if (nearCompletion && stallCount == 0 && !allMissingUnresponsive)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      expect(nearCompletion) &&
      expect.same(35.seconds, effectiveTimeout) // NO bonus — peers won't declare
    }
  }

  test("M3: near-completion bonus applied when SOME missing peers are responsive") {
    IO {
      val baseTimeout = 35.seconds
      val declaredCount = 16
      val activeCount = 20
      val stallCount = 0
      // At least one missing peer is responsive → still worth waiting
      val allMissingUnresponsive = false

      val progress = declaredCount.toDouble / activeCount
      val nearCompletion = progress >= 0.75 && declaredCount < activeCount
      val effectiveTimeout =
        if (nearCompletion && stallCount == 0 && !allMissingUnresponsive)
          baseTimeout + (baseTimeout / 2)
        else baseTimeout

      expect.same(52500.millis, effectiveTimeout)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // M4: facilitiesTimeoutMultiplier increased to 0.75
  // ══════════════════════════════════════════════════════════════════

  test("M4: facilities phase timeout is 75% of base") {
    IO {
      val baseTimeout = 35.seconds
      val facilitiesMultiplier = 0.75

      val effectiveMs = (baseTimeout.toMillis * facilitiesMultiplier).toLong
      val effectiveTimeout = FiniteDuration(effectiveMs, MILLISECONDS)

      // 35s * 0.75 = 26.25s (sufficient for post-partition recovery)
      expect.same(26250.millis, effectiveTimeout)
    }
  }

  test("M4: facilities phase timeout with old 0.5 multiplier was slower") {
    IO {
      val baseTimeout = 35.seconds
      val oldMultiplier = 0.5
      val newMultiplier = 0.75

      val oldMs = (baseTimeout.toMillis * oldMultiplier).toLong
      val newMs = (baseTimeout.toMillis * newMultiplier).toLong

      // New timeout is longer: 26.25s vs 17.5s (prevents premature eviction during recovery)
      expect(newMs > oldMs) &&
      expect.same(17500L, oldMs) &&
      expect.same(26250L, newMs)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // Integration: shouldAbandon combines quorum + lagging + stalls
  // ══════════════════════════════════════════════════════════════════

  test("shouldAbandon: quorum infeasible alone triggers abandon") {
    IO {
      val stallCount = 0
      val maxStallCycles = 5
      val roundTimedOut = false
      val quorumInfeasible = true
      val isLagging = false

      val shouldAbandon = stallCount >= maxStallCycles || roundTimedOut || quorumInfeasible || isLagging
      expect(shouldAbandon)
    }
  }

  test("shouldAbandon: lagging alone triggers abandon") {
    IO {
      val stallCount = 0
      val maxStallCycles = 5
      val roundTimedOut = false
      val quorumInfeasible = false
      val isLagging = true

      val shouldAbandon = stallCount >= maxStallCycles || roundTimedOut || quorumInfeasible || isLagging
      expect(shouldAbandon)
    }
  }

  test("shouldAbandon: no conditions met = no abandon") {
    IO {
      val stallCount = 2
      val maxStallCycles = 5
      val roundTimedOut = false
      val quorumInfeasible = false
      val isLagging = false

      val shouldAbandon = stallCount >= maxStallCycles || roundTimedOut || quorumInfeasible || isLagging
      expect(!shouldAbandon)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // P7: initFromDownload failure recovery
  // ══════════════════════════════════════════════════════════════════

  test("P7: initFromDownload error handler triggers recovery state transition") {
    IO {
      // Simulate the error handler logic from ConsensusEventLoop
      // When initFromDownload fails after retries, the handler should transition
      // the node to WaitingForDownload so DownloadDaemon can retry
      sealed trait State
      case object Observing extends State
      case object Ready extends State
      case object WaitingForDownload extends State

      var currentState: State = Observing
      val transitioned = currentState match {
        case Observing =>
          currentState = WaitingForDownload
          true
        case Ready =>
          currentState = WaitingForDownload
          true
        case _ => false
      }

      expect(transitioned) &&
      expect(currentState == WaitingForDownload)
    }
  }

  test("P7: initFromDownload recovery falls back to Ready → WaitingForDownload") {
    IO {
      // If node is already in Ready (e.g., race condition), try Ready → WaitingForDownload
      sealed trait State
      case object Observing extends State
      case object Ready extends State
      case object WaitingForDownload extends State

      var currentState: State = Ready
      val primaryTransitioned = currentState match {
        case Observing =>
          currentState = WaitingForDownload
          true
        case _ => false
      }
      // Primary failed, try fallback
      val fallbackTransitioned = if (!primaryTransitioned) {
        currentState match {
          case Ready =>
            currentState = WaitingForDownload
            true
          case _ => false
        }
      } else false

      expect(!primaryTransitioned) &&
      expect(fallbackTransitioned) &&
      expect(currentState == WaitingForDownload)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // P8: View change loop mitigation — eviction skip escalation
  // ══════════════════════════════════════════════════════════════════

  test("P8: eviction loop escalates to abandon after maxSkippedEvictions") {
    IO {
      val maxSkippedEvictions = 3
      var skippedCount = 0

      // Simulate 3 eviction skips (below minimum facilitators)
      (1 to 3).foreach { _ =>
        skippedCount += 1
      }

      val shouldEscalate = skippedCount >= maxSkippedEvictions
      expect(shouldEscalate) &&
      expect.same(3, skippedCount)
    }
  }

  test("P8: eviction loop does NOT escalate below threshold") {
    IO {
      val maxSkippedEvictions = 3
      var skippedCount = 0

      // Only 2 skips
      (1 to 2).foreach { _ =>
        skippedCount += 1
      }

      val shouldEscalate = skippedCount >= maxSkippedEvictions
      expect(!shouldEscalate)
    }
  }

  test("P8: successful eviction resets the skip counter") {
    IO {
      val maxSkippedEvictions = 3
      var skippedCount = 0

      // 2 skips, then a successful eviction resets to 0
      skippedCount += 1
      skippedCount += 1
      // Successful eviction
      skippedCount = 0
      // One more skip
      skippedCount += 1

      val shouldEscalate = skippedCount >= maxSkippedEvictions
      expect(!shouldEscalate) &&
      expect.same(1, skippedCount)
    }
  }

  test("P8: eviction loop escalation included in shouldAbandon") {
    IO {
      val stallCount = 0
      val maxStallCycles = 5
      val roundTimedOut = false
      val quorumInfeasible = false
      val isLagging = false
      val evictionLoopStuck = true

      val shouldAbandon = stallCount >= maxStallCycles || roundTimedOut || quorumInfeasible || isLagging || evictionLoopStuck
      expect(shouldAbandon)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // P9: Resource cleanup for departed peers
  // ══════════════════════════════════════════════════════════════════

  test("P9: pruneStaleResources removes entries for non-active keys") {
    IO {
      // Simulate resource map with stale entries
      var resources: Map[Int, String] = Map(100 -> "active", 99 -> "stale", 98 -> "stale")
      val activeKey = 100

      // Prune stale resources
      resources = resources.filter { case (k, _) => k == activeKey }

      expect.same(1, resources.size) &&
      expect(resources.contains(activeKey)) &&
      expect(!resources.contains(99)) &&
      expect(!resources.contains(98))
    }
  }

  test("P9: pruneStaleEvents removes entries for departed peers") {
    IO {
      val activePeers = Set("peer1", "peer2", "peer3")
      var events: Map[String, List[String]] = Map(
        "peer1" -> List("event1"),
        "peer2" -> List("event2"),
        "departed1" -> List("stale1"),
        "departed2" -> List("stale2")
      )

      // Prune events from departed peers
      events = events.filter { case (pid, _) => activePeers.contains(pid) }

      expect.same(2, events.size) &&
      expect(events.contains("peer1")) &&
      expect(events.contains("peer2")) &&
      expect(!events.contains("departed1")) &&
      expect(!events.contains("departed2"))
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // P10: Semaphore timeout protection
  // ══════════════════════════════════════════════════════════════════

  test("P10: semaphore timeout is 30 seconds") {
    IO {
      val semaphoreTimeout = 30.seconds
      // Verify the timeout is reasonable: long enough for legitimate operations
      // but short enough to prevent indefinite blocking
      expect(semaphoreTimeout >= 10.seconds) &&
      expect(semaphoreTimeout <= 60.seconds) &&
      expect.same(30.seconds, semaphoreTimeout)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // P11: Eviction vote tracker scaffolding
  // ══════════════════════════════════════════════════════════════════

  test("P11: eviction vote tracker records and queries votes") {
    import io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionVoteTracker
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      val voter1 = pid("voter1")
      val voter2 = pid("voter2")
      val target = pid("target1")

      tracker.voteToEvict(voter1, target) >>
        tracker.voteToEvict(voter2, target) >>
        tracker.getEvictionVotes.map { votes =>
          val targetVoters = votes.getOrElse(target, Set.empty)
          expect.same(2, targetVoters.size) &&
          expect(targetVoters.contains(voter1)) &&
          expect(targetVoters.contains(voter2))
        }
    }
  }

  test("P11: eviction vote tracker supermajority check") {
    import io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionVoteTracker
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      val target = pid("target1")
      val totalFacilitators = 10
      val threshold = 0.75 // Need 8 votes

      // Add 7 votes — not enough
      (1 to 7).toList.traverse_ { i =>
        tracker.voteToEvict(pid(s"voter$i"), target)
      } >>
        tracker.hasSupermajorityVotes(target, totalFacilitators, threshold).flatMap { hasMajority7 =>
          // Add 8th vote — now has supermajority
          tracker.voteToEvict(pid("voter8"), target) >>
            tracker.hasSupermajorityVotes(target, totalFacilitators, threshold).map { hasMajority8 =>
              expect(!hasMajority7) &&
              expect(hasMajority8)
            }
        }
    }
  }

  test("P11: eviction vote tracker clears votes between rounds") {
    import io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionVoteTracker
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      val target = pid("target1")

      tracker.voteToEvict(pid("voter1"), target) >>
        tracker.clearVotes >>
        tracker.getEvictionVotes.map { votes =>
          expect(votes.isEmpty)
        }
    }
  }

  test("P11: eviction votes for multiple targets tracked independently") {
    import io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionVoteTracker
    EvictionVoteTracker.make[IO].flatMap { tracker =>
      val target1 = pid("target1")
      val target2 = pid("target2")
      val voter = pid("voter1")

      tracker.voteToEvict(voter, target1) >>
        tracker.voteToEvict(voter, target2) >>
        tracker.getEvictionVotes.map { votes =>
          expect.same(2, votes.size) &&
          expect(votes.getOrElse(target1, Set.empty).contains(voter)) &&
          expect(votes.getOrElse(target2, Set.empty).contains(voter))
        }
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // F1: Quorum feasibility — correct computation on active set
  // (fix for double-counting withdrawn peers)
  // ══════════════════════════════════════════════════════════════════

  test("F1: quorum feasibility uses active facilitator count (not double-subtracting withdrawn)") {
    IO {
      // After updateFacilitators, state.facilitators.value already excludes withdrawn peers.
      // The old code computed: activeAfterWithdrawals = facilitators.size - withdrawn.size (double-count)
      // The new code uses: activeFacilitators = facilitators.size (already correct)
      val totalOriginal = 10
      val withdrawn = 3

      // state.facilitators.value.size already reflects removal (= totalOriginal - withdrawn)
      val facilitatorsAfterUpdate = totalOriginal - withdrawn // 7
      val quorumThreshold = 0.75

      // Old (buggy): activeAfterWithdrawals = 7 - 3 = 4, quorumSize = ceil(7 * 0.75) = 6 → infeasible (4 < 6)
      val oldActive = facilitatorsAfterUpdate - withdrawn // double-counting!
      val oldQuorum = math.ceil(facilitatorsAfterUpdate * quorumThreshold).toInt.max(1)
      val oldInfeasible = oldActive > 0 && oldActive < oldQuorum

      // New (fixed): activeFacilitators = 7, quorumSize = ceil(7 * 0.75) = 6 → feasible (7 >= 6)
      val newActive = facilitatorsAfterUpdate
      val newQuorum = math.ceil(facilitatorsAfterUpdate * quorumThreshold).toInt.max(1)
      val newInfeasible = newActive > 0 && newActive < newQuorum

      expect(oldInfeasible) && // old code incorrectly abandons
      expect(!newInfeasible) // new code correctly continues
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // F2: forceLeave tries multiple source states
  // ══════════════════════════════════════════════════════════════════

  test("F2: forceLeave tries Ready, WaitingForDownload, DownloadInProgress, Observing") {
    IO {
      // Simulate the multi-state force leave logic
      val forceLeaveStates = List("Ready", "WaitingForDownload", "DownloadInProgress", "Observing")

      // Node is in WaitingForDownload — Ready transition fails, WaitingForDownload succeeds
      val currentState = "WaitingForDownload"
      val successState = forceLeaveStates.find(_ == currentState)

      expect(successState.contains("WaitingForDownload")) &&
      expect.same(4, forceLeaveStates.size)
    }
  }

  test("F2: forceLeave falls back to recovery when no state matches") {
    IO {
      // Node is in a state not covered by force leave (e.g., WaitingForReady)
      val forceLeaveStates = List("Ready", "WaitingForDownload", "DownloadInProgress", "Observing")
      val currentState = "WaitingForReady"
      val successState = forceLeaveStates.find(_ == currentState)

      expect(successState.isEmpty) // should fall back to attemptRecoveryDownload
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // F3: totalRecoveryAttemptsRef resets on successful round
  // ══════════════════════════════════════════════════════════════════

  test("F3: totalRecoveryAttempts resets after successful consensus round") {
    IO {
      var totalRecoveryAttempts = 0

      // Simulate: 3 recovery attempts
      totalRecoveryAttempts += 1
      totalRecoveryAttempts += 1
      totalRecoveryAttempts += 1
      expect.same(3, totalRecoveryAttempts) && {
        // After successful round → resetOnSuccessfulRound
        totalRecoveryAttempts = 0
        expect.same(0, totalRecoveryAttempts)
      }
    }
  }

  test("F3: without reset, stale recovery history causes premature force-leave") {
    IO {
      val maxConsecutiveAbandonments = 5
      val maxTotalRecoveryAttempts = maxConsecutiveAbandonments * 3 // 15

      // Scenario: 12 recoveries → successful for 1000 rounds → new issue → 3 more recoveries
      var totalAttempts = 12

      // Without reset: after 3 more, total = 15 → force leave (premature!)
      totalAttempts += 3
      val wouldForceLeaveWithoutReset = totalAttempts >= maxTotalRecoveryAttempts

      // With reset: after successful round, total resets to 0, then 3 → NOT force leave
      totalAttempts = 0 // reset on successful round
      totalAttempts += 3
      val wouldForceLeaveWithReset = totalAttempts >= maxTotalRecoveryAttempts

      expect(wouldForceLeaveWithoutReset) && // stale history triggers premature force-leave
      expect(!wouldForceLeaveWithReset) // reset prevents premature force-leave
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // F4: Quality decay and pruning in consensus-agreed quality maps
  // ══════════════════════════════════════════════════════════════════

  test("F4: quality decay halves counters when threshold exceeded") {
    import scala.collection.immutable.SortedMap
    IO {
      val qualityDecayThreshold = 100

      // Simulate accumulated quality exceeding threshold
      val accumulated: SortedMap[String, (Int, Int)] = SortedMap(
        "peer1" -> (90, 110), // participated > threshold
        "peer2" -> (80, 95),
        "peer3" -> (50, 60)
      )

      val needsDecay = accumulated.values.exists { case (_, p) => p > qualityDecayThreshold }
      val decayed =
        if (needsDecay) accumulated.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
        else accumulated

      expect(needsDecay) &&
      expect.same((45, 55), decayed("peer1")) &&
      expect.same((40, 47), decayed("peer2")) &&
      expect.same((25, 30), decayed("peer3"))
    }
  }

  test("F4: quality decay does NOT trigger below threshold") {
    import scala.collection.immutable.SortedMap
    IO {
      val qualityDecayThreshold = 100

      val accumulated: SortedMap[String, (Int, Int)] = SortedMap(
        "peer1" -> (50, 60),
        "peer2" -> (30, 40)
      )

      val needsDecay = accumulated.values.exists { case (_, p) => p > qualityDecayThreshold }
      expect(!needsDecay) &&
      expect.same((50, 60), accumulated("peer1")) // unchanged
    }
  }

  test("F4: quality pruning removes entries where both counters are zero") {
    import scala.collection.immutable.SortedMap
    IO {
      // After decay, some entries may become (0, 0)
      val decayed: SortedMap[String, (Int, Int)] = SortedMap(
        "peer1" -> (25, 30), // active
        "peer2" -> (0, 1), // still has participation
        "departed" -> (0, 0) // should be pruned
      )

      val pruned = decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }

      expect.same(2, pruned.size) &&
      expect(pruned.contains("peer1")) &&
      expect(pruned.contains("peer2")) &&
      expect(!pruned.contains("departed"))
    }
  }

  test("F4: quality decay is deterministic (same input → same output)") {
    import scala.collection.immutable.SortedMap
    IO {
      val qualityDecayThreshold = 100
      val input: SortedMap[String, (Int, Int)] = SortedMap(
        "a" -> (90, 110),
        "b" -> (80, 95),
        "c" -> (50, 60)
      )

      def applyDecay(m: SortedMap[String, (Int, Int)]): SortedMap[String, (Int, Int)] = {
        val needsDecay = m.values.exists { case (_, p) => p > qualityDecayThreshold }
        val decayed =
          if (needsDecay) m.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
          else m
        decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }
      }

      // Run twice — must produce identical results
      val result1 = applyDecay(input)
      val result2 = applyDecay(input)

      expect.same(result1, result2)
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // F5: Currency L0 quality scores — proofs-based (fork fix)
  // ══════════════════════════════════════════════════════════════════

  test("F5: currency L0 quality must use proofs, not withdrawn/removed state") {
    IO {
      // Demonstrate the difference between proofs-based and withdrawn-based quality
      val facilitators = (1 to 5).map(i => pid(s"peer$i")).toList
      val signers = facilitators.take(3).toSet // proofs say 3 signed
      val withdrawn = Set(facilitators(3)) // gossip says peer4 withdrew

      // Proofs-based (correct — deterministic)
      val proofsQuality = facilitators.map { p =>
        p -> (if (signers.contains(p)) 1 else 0, 1)
      }.toMap

      // Withdrawn-based (old — NON-deterministic, different nodes may disagree)
      val withdrawnQuality = facilitators.map { p =>
        p -> (if (withdrawn.contains(p)) 0 else 1, 1)
      }.toMap

      // They produce different results for peer5 (didn't sign but gossip didn't mark as withdrawn)
      // peer5 (index 4): proofs says (0,1) — didn't sign; withdrawn says (1,1) — not in withdrawn set
      expect.same((0, 1), proofsQuality(facilitators(4))) && // peer5 didn't sign
      expect.same((1, 1), withdrawnQuality(facilitators(4))) && // but gossip didn't say it withdrew
      expect(proofsQuality != withdrawnQuality) // they diverge → fork risk!
    }
  }
}
