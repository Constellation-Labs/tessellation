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
}
