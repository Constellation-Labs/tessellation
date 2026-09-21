package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AbandonmentTracker.StaleKeyTelemetry

import weaver.SimpleIOSuite

/** D1 stale-key WARN rate limiting: one initial WARN per key, at most one reminder per interval, reset only on accepted consensus progress
  * (`reset`, wired to `resetOnSuccessfulRound`) and never by the abandonment path's own `RoundCompleted`, bounded retention. The clock is
  * injected so no test sleeps.
  */
object StaleKeyTelemetrySuite extends SimpleIOSuite {

  private def fixture(maxEntries: Int = 32): IO[(Ref[IO, FiniteDuration], StaleKeyTelemetry[IO, Long])] =
    Ref.of[IO, FiniteDuration](1000.seconds).flatMap { clock =>
      StaleKeyTelemetry.make[IO, Long](clock.get, reminderInterval = 1.minute, maxEntries = maxEntries).map((clock, _))
    }

  test("first capture at a key emits the initial WARN; a same-key retry inside the interval is suppressed") {
    fixture().flatMap {
      case (clock, telemetry) =>
        for {
          first <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 5.seconds)
          retry <- telemetry.capture(100L, 99L)
        } yield
          expect(first.exists(_.kind == "initial"), s"first capture must be the initial WARN, got $first")
            .and(expect(first.exists(_.warnsSoFar == 0), "the initial WARN reports zero prior warns"))
            .and(expect(retry.isEmpty, s"a retry 5s later must be rate limited, got $retry"))
    }
  }

  test("at most one reminder per minute, with residence measured from the first local observation") {
    fixture().flatMap {
      case (clock, telemetry) =>
        for {
          _ <- telemetry.observe(100L, 0)
          _ <- clock.update(_ + 10.seconds)
          _ <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 59.seconds)
          early <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 1.second)
          reminder <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 30.seconds)
          again <- telemetry.capture(100L, 99L)
        } yield
          expect(early.isEmpty, "59s after the initial WARN no reminder is due")
            .and(expect(reminder.exists(_.kind == "reminder"), s"exactly 60s later one reminder is due, got $reminder"))
            .and(expect(reminder.exists(_.warnsSoFar == 1), "the reminder counts the initial WARN"))
            .and(expect(reminder.exists(_.residence == 70.seconds), s"residence runs from observe, got ${reminder.map(_.residence)}"))
            .and(expect(again.isEmpty, "a second capture 30s after the reminder is suppressed"))
    }
  }

  test("abandonment (RoundCompleted without accepted progress) does not reset the budget; accepted progress does") {
    fixture().flatMap {
      case (clock, telemetry) =>
        for {
          _ <- telemetry.capture(100L, 99L)
          // performAbandon clears round state and offers RoundCompleted; the next attempt at the same
          // key captures again. There is deliberately no hook for that command: the budget must hold.
          _ <- clock.update(_ + 1.second)
          afterAbandon <- telemetry.capture(100L, 99L)
          _ <- telemetry.reset
          afterProgress <- telemetry.capture(100L, 99L)
        } yield
          expect(afterAbandon.isEmpty, "a same-key retry after abandonment stays rate limited")
            .and(
              expect(
                afterProgress.exists(_.kind == "initial"),
                s"after accepted progress the key gets a fresh initial WARN, got $afterProgress"
              )
            )
    }
  }

  test("keys are independent: a new key gets its own initial WARN while the old one stays limited") {
    fixture().flatMap {
      case (clock, telemetry) =>
        for {
          _ <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 1.second)
          next <- telemetry.capture(101L, 99L)
          old <- telemetry.capture(100L, 99L)
        } yield expect(next.exists(_.kind == "initial"), "key 101 has its own budget").and(expect(old.isEmpty, "key 100 is still limited"))
    }
  }

  test("keys below the installed parent are evicted on capture and the map is capped") {
    fixture(maxEntries = 2).flatMap {
      case (clock, telemetry) =>
        for {
          _ <- telemetry.capture(100L, 99L)
          _ <- telemetry.capture(101L, 99L)
          _ <- telemetry.capture(102L, 99L)
          afterCap <- telemetry.trackedKeys
          _ <- clock.update(_ + 1.second)
          _ <- telemetry.capture(105L, 104L)
          afterParentAdvance <- telemetry.trackedKeys
        } yield
          expect
            .same(Set(101L, 102L), afterCap)
            .and(expect.same(Set(105L), afterParentAdvance))
    }
  }

  test("lastExternalFacilityAgo is unknown until an external Facility is observed, then measured from that observation") {
    fixture().flatMap {
      case (clock, telemetry) =>
        for {
          _ <- telemetry.observe(100L, 0)
          none <- telemetry.capture(100L, 99L)
          _ <- clock.update(_ + 20.seconds)
          _ <- telemetry.observe(100L, 2)
          _ <- clock.update(_ + 45.seconds)
          _ <- telemetry.observe(100L, 2)
          reminder <- telemetry.capture(100L, 99L)
        } yield
          expect(none.exists(_.lastExternalFacilityAgo.isEmpty), "no external Facility yet reports unknown")
            .and(
              expect(
                reminder.exists(_.lastExternalFacilityAgo.contains(45.seconds)),
                s"an unchanged count does not refresh the instant, got ${reminder.map(_.lastExternalFacilityAgo)}"
              )
            )
    }
  }
}
