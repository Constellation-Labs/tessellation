package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.generators.peerGen
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object AdmissionCandidateTipProbeSuite extends SimpleIOSuite with Checkers {

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)
  private val expectedHash = Hash("expected")
  private val differentHash = Hash("different")
  private val rendezvousEntropy = Hash("ab" * 32)

  test("fresh direct readiness accepts the exact expected tip") {
    val tip = ChainTip(ordinal(100L), expectedHash)
    IO.pure(expect(AdmissionTipReadiness.isExact(tip, expectedHash, ordinal(100L).some)))
  }

  test("chain-tip-only endpoint payload reuses the existing optional ChainTip codec") {
    val tip = ChainTip(ordinal(100L), expectedHash)
    val encoded = tip.some.asJson.noSpaces

    IO.pure(expect(decode[Option[ChainTip]](encoded).contains(tip.some)))
  }

  test("fresh direct readiness rejects lagging tips even within the cached-tip tolerance") {
    val oneBehind = ChainTip(ordinal(99L), differentHash)
    val twoBehind = ChainTip(ordinal(98L), differentHash)

    IO.pure(
      expect(!AdmissionTipReadiness.isExact(oneBehind, expectedHash, ordinal(100L).some)) &&
        expect(!AdmissionTipReadiness.isExact(twoBehind, expectedHash, ordinal(100L).some))
    )
  }

  test("fresh direct readiness rejects a conflicting same-ordinal hash and every ahead mismatch") {
    val sameOrdinalConflict = ChainTip(ordinal(100L), differentHash)
    val oneAhead = ChainTip(ordinal(101L), differentHash)
    val farAhead = ChainTip(ordinal(500L), differentHash)

    IO.pure(
      expect(!AdmissionTipReadiness.isExact(sameOrdinalConflict, expectedHash, ordinal(100L).some)) &&
        expect(!AdmissionTipReadiness.isExact(oneAhead, expectedHash, ordinal(100L).some)) &&
        expect(!AdmissionTipReadiness.isExact(farAhead, expectedHash, ordinal(100L).some))
    )
  }

  test("the canonical target is probed once per monitor attempt without walking to a second target") {
    val first = PeerId(Hex("01" * 64))
    val second = PeerId(Hex("02" * 64))
    val readyTip = ChainTip(ordinal(100L), expectedHash)
    val isReady: ChainTip => Boolean = tip => tip.snapshotHash == expectedHash

    IO.pure(
      expect.same(
        first.some,
        AdmissionCandidateTipProbe.targetForRound(List(first, second), Set.empty, Map.empty, isReady)
      ) &&
        expect.same(
          none[PeerId],
          AdmissionCandidateTipProbe.targetForRound(List(first, second), Set(first), Map.empty, isReady)
        ) &&
        expect.same(
          first.some,
          AdmissionCandidateTipProbe.targetForRound(List(first, second), Set.empty, Map.empty, isReady)
        ) &&
        expect.same(
          none[PeerId],
          AdmissionCandidateTipProbe.targetForRound(List(first, second), Set.empty, Map(first -> readyTip), isReady)
        )
    )
  }

  test("a fresh direct response must exactly match the expected parent before it becomes vote evidence") {
    val target = PeerId(Hex("01" * 64))
    val exact = ChainTip(ordinal(100L), expectedHash)
    val behind = ChainTip(ordinal(99L), differentHash)
    val conflict = ChainTip(ordinal(100L), differentHash)

    IO.pure(
      expect.same(
        Map(target -> exact),
        AdmissionCandidateTipProbe.mergeExactResult(Map.empty, (target -> exact.some).some, expectedHash, ordinal(100L).some)
      ) &&
        expect.same(
          Map.empty[PeerId, ChainTip],
          AdmissionCandidateTipProbe.mergeExactResult(Map.empty, (target -> behind.some).some, expectedHash, ordinal(100L).some)
        ) &&
        expect.same(
          Map.empty[PeerId, ChainTip],
          AdmissionCandidateTipProbe.mergeExactResult(Map.empty, (target -> conflict.some).some, expectedHash, ordinal(100L).some)
        )
    )
  }

  test("probation probing fixes one deterministic target and never walks on input permutation") {
    val first = PeerId(Hex("01" * 64))
    val second = PeerId(Hex("02" * 64))
    val third = PeerId(Hex("03" * 64))
    val forward = AdmissionCandidateTipProbe.probationTargetForRound(List(first, second, third).toSet, rendezvousEntropy)
    val reverse = AdmissionCandidateTipProbe.probationTargetForRound(List(third, second, first).toSet, rendezvousEntropy)

    IO.pure(expect(forward.nonEmpty) && expect.same(forward, reverse))
  }

  test("an unavailable fixed probation target does not starve the fixed open target") {
    val probationTarget = PeerId(Hex("01" * 64))
    val openTarget = PeerId(Hex("02" * 64))
    val openTip = ChainTip(ordinal(100L), expectedHash)
    val probes = AdmissionCandidateTipProbe.Probes[IO](
      open = _ => openTip.some.pure[IO],
      probation = _ => none[ChainTip].pure[IO]
    )

    AdmissionCandidateTipProbe
      .runLaneProbes(probes, probationTarget.some, openTarget.some)
      .map { results =>
        val probation = results.collectFirst {
          case (`probationTarget`, AdmissionCandidateTipProbe.Lane.ProbationRecovery, tip) => tip
        }
        val open = results.collectFirst {
          case (`openTarget`, AdmissionCandidateTipProbe.Lane.OpenReady, tip) => tip
        }

        expect.same(none[ChainTip].some, probation) &&
        expect.same(openTip.some.some, open) &&
        expect.same(2, results.size)
      }
  }

  test("a throttled probation lane does not starve the fixed open target") {
    val openTarget = PeerId(Hex("02" * 64))
    val openTip = ChainTip(ordinal(100L), expectedHash)
    val probes = AdmissionCandidateTipProbe.Probes[IO](
      open = _ => openTip.some.pure[IO],
      probation = _ => IO.raiseError(new Throwable("throttled probation probe must not run"))
    )

    AdmissionCandidateTipProbe
      .runLaneProbes(probes, none[PeerId], openTarget.some)
      .map { results =>
        expect.same(
          List((openTarget, AdmissionCandidateTipProbe.Lane.OpenReady, openTip.some)),
          results
        )
      }
  }

  test("probation probe due boundary uses monotonic elapsed time") {
    val last = 10.seconds

    IO.pure(
      expect(AdmissionCandidateTipProbe.isProbeDue(None, 10.seconds, 1.second)) &&
        expect(!AdmissionCandidateTipProbe.isProbeDue(last.some, 10999.millis, 1.second)) &&
        expect(AdmissionCandidateTipProbe.isProbeDue(last.some, 11.seconds, 1.second)) &&
        expect(AdmissionCandidateTipProbe.isProbeDue(last.some, 12.seconds, 1.second))
    )
  }

  test("probation streak requires three fresh exact observations and resets without walking") {
    val target = PeerId(Hex("01" * 64))
    val other = PeerId(Hex("02" * 64))
    val exact = ChainTip(ordinal(100L), expectedHash)
    val conflict = ChainTip(ordinal(100L), differentHash)
    val targetOption = target.some

    val once = AdmissionCandidateTipProbe.updateExactProbationStreak(
      Map(other -> 99),
      targetOption,
      AdmissionCandidateTipProbe.Observation.Attempted(exact.some),
      expectedHash,
      ordinal(100L).some
    )
    val twice = AdmissionCandidateTipProbe.updateExactProbationStreak(
      once,
      targetOption,
      AdmissionCandidateTipProbe.Observation.Attempted(exact.some),
      expectedHash,
      ordinal(100L).some
    )
    val threeTimes = AdmissionCandidateTipProbe.updateExactProbationStreak(
      twice,
      targetOption,
      AdmissionCandidateTipProbe.Observation.Attempted(exact.some),
      expectedHash,
      ordinal(100L).some
    )
    val conflictReset = AdmissionCandidateTipProbe.updateExactProbationStreak(
      threeTimes,
      targetOption,
      AdmissionCandidateTipProbe.Observation.Attempted(conflict.some),
      expectedHash,
      ordinal(100L).some
    )
    val unavailableReset = AdmissionCandidateTipProbe.updateExactProbationStreak(
      threeTimes,
      targetOption,
      AdmissionCandidateTipProbe.Observation.Attempted(none[ChainTip]),
      expectedHash,
      ordinal(100L).some
    )
    val throttledPreserves = AdmissionCandidateTipProbe.updateExactProbationStreak(
      threeTimes,
      targetOption,
      AdmissionCandidateTipProbe.Observation.NotAttempted,
      expectedHash,
      ordinal(100L).some
    )
    val noTargetClears = AdmissionCandidateTipProbe.updateExactProbationStreak(
      threeTimes,
      none[PeerId],
      AdmissionCandidateTipProbe.Observation.NotAttempted,
      expectedHash,
      ordinal(100L).some
    )

    IO.pure(
      expect.same(Map(target -> 1), once) &&
        expect.same(Map(target -> 2), twice) &&
        expect.same(Map(target -> 3), threeTimes) &&
        expect.same(Map(target -> 0), conflictReset) &&
        expect.same(Map(target -> 0), unavailableReset) &&
        expect.same(threeTimes, throttledPreserves) &&
        expect.same(Map.empty[PeerId, Int], noTargetClears) &&
        expect(!threeTimes.contains(other))
    )
  }

  test("a throttled tick cannot emit from a completed probation streak") {
    val target = PeerId(Hex("01" * 64))
    val exact = ChainTip(ordinal(100L), expectedHash)
    val streaks = Map(target -> 2)

    val throttled = AdmissionCandidateTipProbe.readyProbationTarget(
      target.some,
      AdmissionCandidateTipProbe.Observation.NotAttempted,
      streaks,
      minimumStreak = 2,
      alreadyVotedBySelf = Set.empty,
      expectedHash = expectedHash,
      expectedOrdinal = ordinal(100L).some
    )
    val fresh = AdmissionCandidateTipProbe.readyProbationTarget(
      target.some,
      AdmissionCandidateTipProbe.Observation.Attempted(exact.some),
      streaks,
      minimumStreak = 2,
      alreadyVotedBySelf = Set.empty,
      expectedHash = expectedHash,
      expectedOrdinal = ordinal(100L).some
    )

    IO.pure(expect(throttled.isEmpty) && expect.same(Set(target), fresh))
  }

  test("probe reuses latest metadata for one responsive Ready peer") {
    forall(peerGen) { generated =>
      val peer = generated.copy(state = NodeState.Ready)
      val metadata = SnapshotMetadata(ordinal(100L), expectedHash, Hash("parent"))

      for {
        calls <- Ref.of[IO, Int](0)
        result <- AdmissionCandidateTipProbe.probe[IO](
          Set(peer),
          peer.id,
          AdmissionCandidateTipProbe.Lane.OpenReady,
          (_: Peer) => calls.update(_ + 1).as(ChainTip(metadata.ordinal, metadata.hash).some)
        )
        count <- calls.get
      } yield expect.same(ChainTip(metadata.ordinal, metadata.hash).some, result) && expect.same(1, count)
    }
  }

  test("open probe is fail-closed for non-Ready, failure, and timeout") {
    forall(peerGen) { generated =>
      val ready = generated.copy(state = NodeState.Ready)
      val notReady = generated.copy(state = NodeState.WaitingForReady)

      for {
        nonReady <- AdmissionCandidateTipProbe.probe[IO](
          Set(notReady),
          notReady.id,
          AdmissionCandidateTipProbe.Lane.OpenReady,
          (_: Peer) => IO.raiseError(new Throwable("must not fetch"))
        )
        failed <- AdmissionCandidateTipProbe.probe[IO](
          Set(ready),
          ready.id,
          AdmissionCandidateTipProbe.Lane.OpenReady,
          (_: Peer) => IO.raiseError(new Throwable("unavailable"))
        )
        timedOut <- AdmissionCandidateTipProbe.probe[IO](
          Set(ready),
          ready.id,
          AdmissionCandidateTipProbe.Lane.OpenReady,
          (_: Peer) => IO.never[Option[ChainTip]],
          10.millis
        )
      } yield expect(nonReady.isEmpty) && expect(failed.isEmpty) && expect(timedOut.isEmpty)
    }
  }

  test("probation probe accepts pre-Ready recovery states while open probe rejects them") {
    forall(peerGen) { generated =>
      val tip = ChainTip(ordinal(100L), expectedHash)
      val observing = generated.copy(state = NodeState.Observing)
      val waiting = generated.copy(state = NodeState.WaitingForReady)
      val fetch: Peer => IO[Option[ChainTip]] = _ => tip.some.pure[IO]

      for {
        observingProbation <- AdmissionCandidateTipProbe.probe[IO](
          Set(observing),
          observing.id,
          AdmissionCandidateTipProbe.Lane.ProbationRecovery,
          fetch
        )
        waitingProbation <- AdmissionCandidateTipProbe.probe[IO](
          Set(waiting),
          waiting.id,
          AdmissionCandidateTipProbe.Lane.ProbationRecovery,
          fetch
        )
        observingOpen <- AdmissionCandidateTipProbe.probe[IO](
          Set(observing),
          observing.id,
          AdmissionCandidateTipProbe.Lane.OpenReady,
          (_: Peer) => IO.raiseError(new Throwable("open lane must not probe Observing"))
        )
      } yield
        expect.same(tip.some, observingProbation) &&
          expect.same(tip.some, waitingProbation) &&
          expect(observingOpen.isEmpty)
    }
  }
}
