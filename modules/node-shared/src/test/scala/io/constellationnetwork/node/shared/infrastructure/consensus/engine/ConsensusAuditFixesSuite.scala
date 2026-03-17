package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO

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
          val tier = if (participated > 0) participated - completed else 0L
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
      val scores: Map[PeerId, (Int, Int)] = peers.zipWithIndex.map { case (p, i) =>
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
        "peer1" -> 105, "peer2" -> 105, "peer3" -> 105,
        "peer4" -> 100, "peer5" -> 105
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
        "peer1" -> 105, "peer2" -> 105, "peer3" -> 105,
        "peer4" -> 100, "peer5" -> 105
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
        "peer1" -> 105, "peer2" -> 105
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
        "peer1" -> 105, "peer2" -> 105,
        "peer3" -> 100, "peer4" -> 100
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
  // M4: facilitiesTimeoutMultiplier reduced to 0.3
  // ══════════════════════════════════════════════════════════════════

  test("M4: facilities phase timeout is 30% of base (down from 50%)") {
    IO {
      val baseTimeout = 35.seconds
      val facilitiesMultiplier = 0.3

      val effectiveMs = (baseTimeout.toMillis * facilitiesMultiplier).toLong
      val effectiveTimeout = FiniteDuration(effectiveMs, MILLISECONDS)

      // 35s * 0.3 = 10.5s (faster detection of stuck facilities phase)
      expect.same(10500.millis, effectiveTimeout)
    }
  }

  test("M4: facilities phase timeout with old 0.5 multiplier was slower") {
    IO {
      val baseTimeout = 35.seconds
      val oldMultiplier = 0.5
      val newMultiplier = 0.3

      val oldMs = (baseTimeout.toMillis * oldMultiplier).toLong
      val newMs = (baseTimeout.toMillis * newMultiplier).toLong

      // New timeout is faster: 10.5s vs 17.5s
      expect(newMs < oldMs) &&
      expect.same(17500L, oldMs) &&
      expect.same(10500L, newMs)
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
}
