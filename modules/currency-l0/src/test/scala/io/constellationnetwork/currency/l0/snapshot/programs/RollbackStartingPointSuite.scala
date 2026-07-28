package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import retry.RetryPolicies.limitRetries
import weaver.SimpleIOSuite

/** Pins the rollback fast-path peer-sweep semantics (`Rollback.resolveStartingGlobalSnapshot`).
  *
  * `GlobalL0Service.pullGlobalSnapshot(ordinal)` draws ONE random GL0 peer per call and converts
  * any error (including a 404 from a peer holding no deep history) to None. Before the retry, a
  * single unlucky draw silently degraded the rollback into a one-by-one walk from the tip
  * (observed on IntegrationNet: 4,310 snapshots, 73 minutes). Each retry re-rolls the peer, so
  * the policy is a peer sweep with replacement; these tests pin the sweep size, the terminal
  * fallback, and that the fast path never fires without the sync-data hint.
  */
object RollbackStartingPointSuite extends SimpleIOSuite {

  private val latest = 0
  private val target = 42
  private val targetOrdinal = SnapshotOrdinal.unsafeApply(5840858L)
  // Production attempt budget without the delay so failure tests count exact calls, not sleep.
  private val instantPolicy = limitRetries[IO](15)

  private def countingPull(outcome: Long => IO[Option[Int]]): IO[(Ref[IO, Long], SnapshotOrdinal => IO[Option[Int]])] =
    Ref.of[IO, Long](0L).map { counter =>
      counter -> ((_: SnapshotOrdinal) => counter.updateAndGet(_ + 1).flatMap(outcome))
    }

  private def resolve(pull: SnapshotOrdinal => IO[Option[Int]], hint: Option[SnapshotOrdinal] = targetOrdinal.some): IO[Int] =
    Rollback.resolveStartingGlobalSnapshot[IO, Int](latest, hint, pull, instantPolicy, "DAGRollbackSuiteMetagraph")

  test("a miss on the drawn peer is retried and the fast path still lands on the target") {
    for {
      (counter, pull) <- countingPull(n => if (n <= 3L) none[Int].pure[IO] else target.some.pure[IO])
      result <- resolve(pull)
      calls <- counter.get
    } yield expect.same(target, result) and
      expect(calls == 4L, s"expected 4 pull attempts (3 misses then a hit), got $calls")
  }

  test("errors from the drawn peer are retried like misses") {
    for {
      (counter, pull) <- countingPull(n => if (n <= 2L) IO.raiseError(new Exception("peer down")) else target.some.pure[IO])
      result <- resolve(pull)
      calls <- counter.get
    } yield expect.same(target, result) and
      expect(calls == 3L, s"expected 3 pull attempts (2 errors then a hit), got $calls")
  }

  test("exhausted misses fall back to the latest snapshot after the full sweep") {
    for {
      (counter, pull) <- countingPull(_ => none[Int].pure[IO])
      result <- resolve(pull)
      calls <- counter.get
    } yield expect.same(latest, result) and
      expect(calls == 16L, s"expected 16 pull attempts (1 initial + 15 retries), got $calls")
  }

  test("exhausted errors fall back to the latest snapshot instead of failing the rollback") {
    for {
      (counter, pull) <- countingPull(_ => IO.raiseError(new Exception("always down")))
      result <- resolve(pull)
      calls <- counter.get
    } yield expect.same(latest, result) and
      expect(calls == 16L, s"expected 16 pull attempts (1 initial + 15 retries), got $calls")
  }

  test("no sync-data hint means the fast-path pull never fires") {
    for {
      (counter, pull) <- countingPull(_ => target.some.pure[IO])
      result <- resolve(pull, hint = none)
      calls <- counter.get
    } yield expect.same(latest, result) and
      expect(calls == 0L, s"pull must not be called without the sync-data hint, got $calls")
  }

  pureTest("the production policy sweeps 15 retries at a 1 second cadence") {
    val shown = Rollback.fastPathRetryPolicy[IO].toString
    expect(shown.contains("15"), s"policy should retry 15 times: $shown") and
      expect(shown.contains("1 second"), s"policy should delay 1 second between draws: $shown")
  }
}
