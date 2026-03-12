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
        val scores = facilitators.map(_ -> 0.5).toMap
        val leader1 = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        val leader2 = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        expect.same(leader1, leader2)
      }
    }
  }

  test("selectLeaderWeighted with all equal scores matches selectLeader when qualityWeight=0") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val scores = facilitators.map(_ -> 1.0).toMap
        val standard = selector.selectLeader(facilitators, entropy)
        val weighted = selector.selectLeaderWeighted(facilitators, entropy, 0, scores, qualityWeight = 0.0)
        expect.same(standard, weighted)
      }
    }
  }

  test("selectLeaderWeighted prefers high-quality peer when qualityWeight is high") {
    IO {
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val entropy = Hash.empty

      // Give one peer very high quality, rest very low
      val highQualityPeer = peers.head
      val scores = peers.map(p => p -> (if (p == highQualityPeer) 1.0 else 0.0)).toMap

      // With very high quality weight, high-quality peer should be selected more often across entropies
      val entropies = (0 until 20).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val selections = entropies.map(e => selector.selectLeaderWeighted(peers, e, 0, scores, qualityWeight = 0.9))
      val highQualityCount = selections.count(_ == highQualityPeer)

      // High-quality peer should be selected more than fair share (1/5 = 4 times out of 20)
      expect(highQualityCount > 4)
    }
  }

  test("selectLeaderWeighted rotates with viewNumber") {
    IO {
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val entropy = Hash.empty
      val scores = peers.map(_ -> 0.8).toMap

      val leaders = (0 until peers.size).map(v => selector.selectLeaderWeighted(peers, entropy, v, scores))
      // All leaders should be distinct for different view numbers
      expect.same(peers.size, leaders.distinct.size)
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
}
