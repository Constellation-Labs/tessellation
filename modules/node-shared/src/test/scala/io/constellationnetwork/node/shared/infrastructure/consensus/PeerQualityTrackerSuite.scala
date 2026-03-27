package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object PeerQualityTrackerSuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 20)

  def peerIdsGen(n: Int): Gen[List[PeerId]] =
    Gen.containerOfN[Set, PeerId](n, arbitrary[PeerId]).map(_.toList)

  private def pid(name: String): PeerId = PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  // === Score computation tests ===

  test("new peer has quality score of 1.0") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      score <- tracker.getQualityScore(pid("unknown"))
    } yield expect.same(1.0, score)
  }

  test("peer with all rounds successful has score 1.0") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer1")
      _ <- tracker.recordRoundSuccess(Set(p))
      _ <- tracker.recordRoundSuccess(Set(p))
      _ <- tracker.recordRoundSuccess(Set(p))
      score <- tracker.getQualityScore(p)
    } yield expect.same(1.0, score)
  }

  test("peer with all rounds abandoned has score 0.0") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer1")
      _ <- tracker.recordRoundAbandoned(Set(p))
      _ <- tracker.recordRoundAbandoned(Set(p))
      _ <- tracker.recordRoundAbandoned(Set(p))
      score <- tracker.getQualityScore(p)
    } yield expect.same(0.0, score)
  }

  test("view change reduces quality score") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer1")
      _ <- tracker.recordRoundSuccess(Set(p))
      _ <- tracker.recordRoundSuccess(Set(p))
      scoreBefore <- tracker.getQualityScore(p)
      _ <- tracker.recordViewChange(p)
      scoreAfter <- tracker.getQualityScore(p)
    } yield expect(scoreBefore > scoreAfter)
  }

  test("mixed success and abandon gives intermediate score") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer1")
      _ <- tracker.recordRoundSuccess(Set(p))
      _ <- tracker.recordRoundAbandoned(Set(p))
      score <- tracker.getQualityScore(p)
    } yield expect(score > 0.0) && expect(score < 1.0)
  }

  // === Multi-peer tracking ===

  test("tracks multiple peers independently") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      good = pid("good")
      bad = pid("bad")
      _ <- tracker.recordRoundSuccess(Set(good, bad))
      _ <- tracker.recordRoundSuccess(Set(good))
      _ <- tracker.recordRoundAbandoned(Set(bad))
      _ <- tracker.recordViewChange(bad)
      goodScore <- tracker.getQualityScore(good)
      badScore <- tracker.getQualityScore(bad)
    } yield expect(goodScore > badScore)
  }

  test("getQualityScores returns all tracked peers") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      _ <- tracker.recordRoundSuccess(Set(pid("p1"), pid("p2"), pid("p3")))
      scores <- tracker.getQualityScores
    } yield expect.same(3, scores.size)
  }

  // === Score formula verification ===

  test("score = completionRate * (1 - viewChangeRate)") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer")
      // 3 rounds participated, 2 completed, 1 view change
      _ <- tracker.recordRoundSuccess(Set(p))
      _ <- tracker.recordRoundSuccess(Set(p))
      _ <- tracker.recordRoundAbandoned(Set(p))
      _ <- tracker.recordViewChange(p)
      score <- tracker.getQualityScore(p)
      // completionRate = 2/3, viewChangeRate = 1/3
      // score = (2/3) * (1 - 1/3) = (2/3) * (2/3) = 4/9 ≈ 0.4444
      expected = (2.0 / 3.0) * (1.0 - 1.0 / 3.0)
    } yield expect(Math.abs(score - expected) < 0.001)
  }

  // === Decay behavior ===

  test("scores remain bounded after many rounds") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer")
      _ <- (1 to 100).toList.traverse_(_ => tracker.recordRoundSuccess(Set(p)))
      score <- tracker.getQualityScore(p)
    } yield expect(score >= 0.0) && expect(score <= 1.0)
  }

  // === recordRoundAbandoned does not count as completed ===

  test("abandoned round counts as participated but not completed") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer")
      _ <- tracker.recordRoundAbandoned(Set(p))
      score <- tracker.getQualityScore(p)
    } yield expect.same(0.0, score)
  }

  // === View change without participation ===

  test("view change on peer with no rounds still reduces score") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("peer")
      _ <- tracker.recordViewChange(p)
      _ <- tracker.recordRoundSuccess(Set(p))
      score <- tracker.getQualityScore(p)
      // 1 participated, 1 completed, 1 view change
      // completionRate = 1/1 = 1.0, viewChangeRate = 1/1 = 1.0
      // score = 1.0 * (1.0 - 1.0) = 0.0
    } yield expect.same(0.0, score)
  }

  // === Property test: score always in [0, 1] ===

  test("quality score is always in [0.0, 1.0]") {
    forall(peerIdsGen(5)) { peers =>
      for {
        tracker <- PeerQualityTracker.make[IO]
        _ <- tracker.recordRoundSuccess(peers.toSet)
        _ <- tracker.recordRoundAbandoned(peers.take(2).toSet)
        _ <- peers.take(1).traverse_(p => tracker.recordViewChange(p))
        scores <- tracker.getQualityScores
      } yield expect(scores.values.forall(s => s >= 0.0 && s <= 1.0))
    }
  }

  // === Recovery behavior ===

  test("bad score recovers after many successful rounds") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      p = pid("recovering")
      // Trash the score first
      _ <- (1 to 5).toList.traverse_(_ => tracker.recordRoundAbandoned(Set(p)))
      _ <- (1 to 3).toList.traverse_(_ => tracker.recordViewChange(p))
      badScore <- tracker.getQualityScore(p)
      // Now many successful rounds
      _ <- (1 to 50).toList.traverse_(_ => tracker.recordRoundSuccess(Set(p)))
      recoveredScore <- tracker.getQualityScore(p)
    } yield expect(badScore < 0.2) && expect(recoveredScore > 0.8)
  }

  // === Concurrent peer tracking ===

  test("peer not involved in round is not affected") {
    for {
      tracker <- PeerQualityTracker.make[IO]
      active = pid("active")
      bystander = pid("bystander")
      _ <- tracker.recordRoundSuccess(Set(active))
      _ <- tracker.recordRoundAbandoned(Set(active))
      bystanderScore <- tracker.getQualityScore(bystander)
    } yield expect.same(1.0, bystanderScore)
  }
}
