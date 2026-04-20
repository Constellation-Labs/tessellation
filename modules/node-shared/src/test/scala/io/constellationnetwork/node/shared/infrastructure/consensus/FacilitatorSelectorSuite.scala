package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object FacilitatorSelectorSuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 40)

  val selector: FacilitatorSelector = FacilitatorSelector.make(Some(10))

  private def pid(name: String): PeerId = PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(5, 20)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  // === selectLeader tests ===

  test("selectLeader is deterministic") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val leader1 = selector.selectLeader(facilitators, entropy)
        val leader2 = selector.selectLeader(facilitators, entropy)
        expect.same(leader1, leader2)
      }
    }
  }

  test("selectLeader rotates with viewNumber") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val leaders = (0 until facilitators.size).map(v => selector.selectLeader(facilitators, entropy, v))
        // All leaders should be different facilitators (for distinct view numbers < facilitator count)
        expect.same(facilitators.size, leaders.distinct.size)
      }
    }
  }

  test("selectLeader wraps around viewNumber") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val leader0 = selector.selectLeader(facilitators, entropy, 0)
        val leaderWrapped = selector.selectLeader(facilitators, entropy, facilitators.size)
        expect.same(leader0, leaderWrapped)
      }
    }
  }

  // === selectLeaderWeighted tests ===

  test("selectLeaderWeighted is deterministic with same quality scores") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        // (completed=5, participated=10) → 50% quality for all
        val scores = facilitators.map(_ -> (5, 10)).toMap
        val leader1 = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        val leader2 = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        expect.same(leader1, leader2)
      }
    }
  }

  test("selectLeaderWeighted with all zero-failure scores matches pure rendezvous ordering") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        // All perfect: (10, 10) → 0 failures → all tier 0 → pure rendezvous
        val scores = facilitators.map(_ -> (10, 10)).toMap
        val standard = selector.selectLeader(facilitators, entropy)
        val weighted = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        expect.same(standard, weighted)
      }
    }
  }

  test("selectLeaderWeighted prefers high-quality peer (fewer failures)") {
    IO {
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList

      // Give one peer perfect quality (0 failures), rest very poor (9 out of 10 failed)
      val highQualityPeer = peers.head
      val scores = peers.map(p => p -> (if (p == highQualityPeer) (10, 10) else (1, 10))).toMap

      // High-quality peer (tier=0) should beat poor peers (tier=9) across entropies
      val entropies = (0 until 20).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val selections = entropies.map(e => selector.selectLeaderWeighted(peers, e, 0, scores))
      val highQualityCount = selections.count(_ == highQualityPeer)

      // High-quality peer should be selected more than fair share (1/5 = 4 times out of 20)
      expect(highQualityCount > 4)
    }
  }

  test("selectLeaderWeighted rotates with viewNumber") {
    IO {
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val entropy = Hash.empty
      // (8, 10) = 2 failures each → same tier for all → view rotation works
      val scores = peers.map(_ -> (8, 10)).toMap

      val leaders = (0 until peers.size).map(v => selector.selectLeaderWeighted(peers, entropy, v, scores))
      // All leaders should be distinct for different view numbers
      expect.same(peers.size, leaders.distinct.size)
    }
  }

  test("selectLeaderWeighted integer tier is deterministic across invocations") {
    IO {
      // Verify that integer-only tier computation produces identical results
      // This is the key safety test — float-to-long conversion was the fork risk
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropy = Hash.fromBytes("test-entropy".getBytes("UTF-8"))
      val scores: Map[PeerId, (Int, Int)] = peers.zipWithIndex.map {
        case (p, i) =>
          p -> (i, 10) // varying quality: 0/10, 1/10, ... 9/10
      }.toMap

      val results = (1 to 100).map(_ => selector.selectLeaderWeighted(peers, entropy, 0, scores))
      // Must be perfectly deterministic
      expect(results.distinct.size == 1)
    }
  }

  // === select (facilitator subset) tests ===

  test("select returns all candidates when fewer than maxCount") {
    IO {
      val smallSelector = FacilitatorSelector.make(Some(20))
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val result = smallSelector.select(peers, Hash.empty)
      expect.same(5, result.size) && expect.same(peers.sorted, result)
    }
  }

  test("select returns maxCount candidates when more available") {
    IO {
      val smallSelector = FacilitatorSelector.make(Some(3))
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val result = smallSelector.select(peers, Hash.empty)
      expect.same(3, result.size)
    }
  }

  test("select is deterministic") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val result1 = selector.select(facilitators, entropy)
        val result2 = selector.select(facilitators, entropy)
        expect.same(result1, result2)
      }
    }
  }

  test("select with None maxCount returns all candidates") {
    IO {
      val unlimitedSelector = FacilitatorSelector.make(None)
      val peers = (1 to 20).map(i => pid(s"peer$i")).toList
      val result = unlimitedSelector.select(peers, Hash.empty)
      expect.same(20, result.size)
    }
  }

  // === Empty list guard tests ===

  test("selectLeader throws on empty facilitators list") {
    IO {
      val caught = scala.util.Try(selector.selectLeader(List.empty, Hash.empty))
      expect(caught.isFailure) && expect(caught.failed.get.isInstanceOf[IllegalArgumentException])
    }
  }

  test("selectLeaderWeighted throws on empty facilitators list") {
    IO {
      val caught = scala.util.Try(selector.selectLeaderWeighted(List.empty, Hash.empty))
      expect(caught.isFailure) && expect(caught.failed.get.isInstanceOf[IllegalArgumentException])
    }
  }

  // === Entropy-based selection tests ===

  test("select produces different subsets with different entropy") {
    IO {
      val smallSelector = FacilitatorSelector.make(Some(3))
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropies = (0 until 10).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val subsets = entropies.map(e => smallSelector.select(peers, e))
      // Not all subsets should be identical (extremely unlikely with different entropies)
      expect(subsets.distinct.size > 1)
    }
  }

  test("selectLeader changes with different entropy") {
    IO {
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropies = (0 until 10).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val leaders = entropies.map(e => selector.selectLeader(peers, e))
      // Not all leaders should be the same with different entropy
      expect(leaders.distinct.size > 1)
    }
  }

  // === Leader graduation filter tests ===
  //
  // These tests exercise the caller-side pattern used in both GlobalSnapshotConsensusStateCreator
  // and CurrencySnapshotConsensusStateCreator: filter `active` to peers with `participated >=
  // minParticipationObservations`, fall back to full `active` if nobody qualifies, then pass the
  // resulting pool to selectLeaderWeighted. The selector itself is unchanged; the filter prevents
  // untracked peers (tier=0 by default) from tying with proven peers and winning by rendezvous.

  private def selectGraduatedLeader(
    active: List[PeerId],
    entropy: Hash,
    scores: Map[PeerId, (Int, Int)],
    threshold: Int,
    viewNumber: Int = 0
  ): PeerId = {
    val graduated = active.filter { p =>
      val (_, participated) = scores.getOrElse(p, (0, 0))
      participated >= threshold
    }
    val pool = if (graduated.nonEmpty) graduated else active
    selector.selectLeaderWeighted(pool, entropy, viewNumber, scores)
  }

  test("graduation filter: unproven peer never selected when proven peers exist") {
    IO {
      val proven = (1 to 3).map(i => pid(s"proven$i")).toList
      val unproven = (1 to 2).map(i => pid(s"unproven$i")).toList
      val active = proven ++ unproven
      val scores: Map[PeerId, (Int, Int)] =
        (proven ++ unproven).map(p => p -> (if (proven.contains(p)) (10, 10) else (0, 0))).toMap
      val threshold = 5

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val leaders = entropies.map(e => selectGraduatedLeader(active, e, scores, threshold))

      expect(leaders.forall(proven.contains), s"unproven peer selected: ${leaders.filterNot(proven.contains)}")
    }
  }

  test("graduation filter: falls back to active when nobody meets threshold (genesis / cold start)") {
    IO {
      // All peers have participated < threshold. Filter would return empty. Fallback to active.
      // Selection should still work and be deterministic across entropies.
      val active = (1 to 5).map(i => pid(s"peer$i")).toList
      val scores: Map[PeerId, (Int, Int)] = active.map(_ -> (0, 0)).toMap
      val threshold = 5
      val entropy = Hash.fromBytes("cold-start".getBytes("UTF-8"))

      val leader = selectGraduatedLeader(active, entropy, scores, threshold)

      expect(active.contains(leader), s"leader $leader not in active pool")
    }
  }

  test("graduation filter: falls back to active when peerQuality map is empty") {
    IO {
      val active = (1 to 5).map(i => pid(s"peer$i")).toList
      val scores = Map.empty[PeerId, (Int, Int)]
      val threshold = 5
      val entropy = Hash.fromBytes("empty-quality".getBytes("UTF-8"))

      val leader = selectGraduatedLeader(active, entropy, scores, threshold)

      expect(active.contains(leader), s"leader $leader not in active pool")
    }
  }

  test("graduation filter: view-change rotates only through graduated pool") {
    IO {
      // Three proven peers, two unproven. View rotation must cycle through proven ONLY.
      val proven = (1 to 3).map(i => pid(s"proven$i")).toList
      val unproven = (1 to 2).map(i => pid(s"unproven$i")).toList
      val active = proven ++ unproven
      val scores: Map[PeerId, (Int, Int)] =
        (proven ++ unproven).map(p => p -> (if (proven.contains(p)) (10, 10) else (0, 0))).toMap
      val threshold = 5
      val entropy = Hash.fromBytes("rotate".getBytes("UTF-8"))

      val leaders = (0 until 10).map(v => selectGraduatedLeader(active, entropy, scores, threshold, v))

      expect(leaders.forall(proven.contains), "view rotation leaked into unproven pool").and(
        expect.same(proven.size, leaders.distinct.size)
      )
    }
  }

  test("graduation filter: single graduated peer always wins") {
    IO {
      // Only one peer meets threshold. Regardless of entropy, that peer should always lead.
      val soleProven = pid("sole-proven")
      val unproven = (1 to 4).map(i => pid(s"unproven$i")).toList
      val active = soleProven +: unproven
      val scores: Map[PeerId, (Int, Int)] =
        active.map(p => p -> (if (p == soleProven) (10, 10) else (0, 0))).toMap
      val threshold = 5

      val entropies = (0 until 20).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val leaders = entropies.map(e => selectGraduatedLeader(active, e, scores, threshold))

      expect(leaders.forall(_ == soleProven))
    }
  }
}
