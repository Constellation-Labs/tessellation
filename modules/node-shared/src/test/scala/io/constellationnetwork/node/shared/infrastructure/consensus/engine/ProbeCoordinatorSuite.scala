package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AbandonmentTracker.{StaleKeyTelemetry, SuppressedBy}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ProbeCoordinator.{Result, Scope, Suppression}

import weaver.SimpleIOSuite

/** B3' scheduling contract: atomic in-flight reservation, completion-based per-scope cooldown, residence gate, late-result rejection across
  * a parent/generation change, and residence kept across same-key retries. The clock is injected; no test sleeps.
  */
object ProbeCoordinatorSuite extends SimpleIOSuite {

  private val interval = 43.seconds
  private val cooldown = 30.seconds
  private val scopeA = Scope(100L, 1L)
  private val scopeB = Scope(101L, 1L)
  private val resident: Option[FiniteDuration] = 60.seconds.some

  private def fixture: IO[(Ref[IO, FiniteDuration], ProbeCoordinator[IO, Long])] =
    Ref.of[IO, FiniteDuration](1000.seconds).flatMap { clock =>
      ProbeCoordinator.make[IO, Long](clock.get, interval, cooldown).map((clock, _))
    }

  private def current: IO[Boolean] = true.pure[IO]

  test("a second caller while a unit is in flight is suppressed with in_flight and never runs a second unit") {
    fixture.flatMap {
      case (_, coordinator) =>
        for {
          gate <- Deferred[IO, Unit]
          runs <- Ref.of[IO, Int](0)
          first <- coordinator.run(scopeA, resident, current)(runs.update(_ + 1) >> gate.get.as("first")).start
          _ <- runs.get.iterateUntil(_ == 1)
          second <- coordinator.run(scopeA, resident, current)(runs.update(_ + 1).as("second"))
          _ <- gate.complete(())
          firstResult <- first.joinWithNever
          total <- runs.get
        } yield
          expect(second == Result.Suppressed(Suppression.InFlight), s"second caller must be in_flight, got $second")
            .and(
              expect(
                second match { case Result.Suppressed(s) => s.suppressedBy == SuppressedBy.InFlight; case _ => false },
                "D2 label is in_flight"
              )
            )
            .and(expect(firstResult == Result.Completed("first"), s"the reserved unit completes normally, got $firstResult"))
            .and(expect(total == 1, s"exactly one unit ran, got $total"))
    }
  }

  test("completion-based cooldown holds the same scope for 30s and releases a different scope immediately") {
    fixture.flatMap {
      case (clock, coordinator) =>
        for {
          _ <- coordinator.run(scopeA, resident, current)(IO.pure(()))
          _ <- clock.update(_ + 10.seconds)
          sameScopeEarly <- coordinator.run(scopeA, resident, current)(IO.pure(()))
          otherScope <- coordinator.run(scopeB, resident, current)(IO.pure(()))
          _ <- clock.update(_ + 20.seconds)
          sameScopeLater <- coordinator.run(scopeA, resident, current)(IO.pure(()))
        } yield
          expect(
            sameScopeEarly == Result.Suppressed(Suppression.Cooldown(20.seconds)),
            s"10s after completion the same scope is in cooldown with 20s remaining, got $sameScopeEarly"
          ).and(expect(otherScope == Result.Completed(()), s"a different scope is not held by the old cooldown, got $otherScope"))
            .and(
              expect(sameScopeLater == Result.Completed(()), s"exactly 30s after completion the same scope runs again, got $sameScopeLater")
            )
    }
  }

  test("the cooldown is measured from completion, not from the start of the unit") {
    fixture.flatMap {
      case (clock, coordinator) =>
        for {
          _ <- coordinator.run(scopeA, resident, current)(clock.update(_ + 25.seconds))
          _ <- clock.update(_ + 10.seconds)
          early <- coordinator.run(scopeA, resident, current)(IO.pure(()))
          _ <- clock.update(_ + 20.seconds)
          later <- coordinator.run(scopeA, resident, current)(IO.pure(()))
        } yield
          expect(
            early == Result.Suppressed(Suppression.Cooldown(20.seconds)),
            s"start-based cooldown would already have expired, got $early"
          )
            .and(expect(later == Result.Completed(()), s"30s after completion the scope runs, got $later"))
    }
  }

  test("residence below one time-trigger interval is suppressed under the cooldown label; unknown residence is eligible") {
    fixture.flatMap {
      case (_, coordinator) =>
        for {
          young <- coordinator.run(scopeA, 10.seconds.some, current)(IO.pure(()))
          unknown <- coordinator.run(scopeA, None, current)(IO.pure(()))
          eligibility <- coordinator.eligibility(scopeB, 42.seconds.some)
          exact <- coordinator.run(scopeB, 43.seconds.some, current)(IO.pure(()))
        } yield
          expect(young == Result.Suppressed(Suppression.Residence(33.seconds)), s"10s residence needs 33s more, got $young")
            .and(
              expect(
                young match { case Result.Suppressed(s) => s.suppressedBy == SuppressedBy.Cooldown; case _ => false },
                "reported as cooldown"
              )
            )
            .and(
              expect(
                young match { case Result.Suppressed(s) => s.detail.startsWith("residence_"); case _ => false },
                "detail names residence"
              )
            )
            .and(expect(unknown == Result.Completed(()), s"unknown residence is eligible (load control fails open), got $unknown"))
            .and(expect(eligibility == Some(Suppression.Residence(1.second)), s"read-only eligibility mirrors run, got $eligibility"))
            .and(expect(exact == Result.Completed(()), s"residence equal to the interval is eligible, got $exact"))
    }
  }

  test("a unit whose parent/generation moved on while it ran is returned as Stale and still starts the cooldown") {
    fixture.flatMap {
      case (_, coordinator) =>
        for {
          stillCurrent <- Ref.of[IO, Boolean](true)
          late <- coordinator.run(scopeA, resident, stillCurrent.get)(stillCurrent.set(false).as("evidence"))
          next <- coordinator.run(scopeA, resident, current)(IO.pure("again"))
          state <- coordinator.state
        } yield
          expect(late == Result.Stale("evidence"), s"a late result is rejected, got $late")
            .and(
              expect(
                next match { case Result.Suppressed(Suppression.Cooldown(_)) => true; case _ => false },
                s"the stale unit still counts for cooldown, got $next"
              )
            )
            .and(expect(state.inFlight.isEmpty, "the reservation is released after a stale result"))
    }
  }

  test("the reservation is released when the unit fails, so the next caller is not in_flight forever") {
    fixture.flatMap {
      case (clock, coordinator) =>
        for {
          failed <- coordinator.run(scopeA, resident, current)(IO.raiseError[Unit](new Exception("boom"))).attempt
          state <- coordinator.state
          _ <- clock.update(_ + cooldown)
          next <- coordinator.run(scopeA, resident, current)(IO.pure(()))
        } yield
          expect(failed.isLeft, "the unit's error propagates to the caller")
            .and(expect(state.inFlight.isEmpty, "the reservation is released on failure"))
            .and(expect(next == Result.Completed(()), s"after the cooldown the scope runs again, got $next"))
    }
  }

  test("residence is kept across same-key retries and forgotten only on accepted progress") {
    Ref.of[IO, FiniteDuration](1000.seconds).flatMap { clock =>
      StaleKeyTelemetry.make[IO, Long](clock.get, reminderInterval = 1.minute, maxEntries = 32).flatMap { telemetry =>
        for {
          _ <- telemetry.observe(100L, 0)
          _ <- clock.update(_ + 20.seconds)
          _ <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 30.seconds)
          // A same-key retry re-observes the key: the first-seen instant must not move.
          _ <- telemetry.observe(100L, 0)
          afterRetry <- telemetry.residenceOf(100L)
          untracked <- telemetry.residenceOf(101L)
          _ <- telemetry.reset
          afterProgress <- telemetry.residenceOf(100L)
        } yield
          expect(afterRetry.contains(50.seconds), s"residence runs from the first observation across retries, got $afterRetry")
            .and(expect(untracked.isEmpty, "an untracked key has no residence"))
            .and(expect(afterProgress.isEmpty, "accepted progress forgets the key"))
      }
    }
  }
}
