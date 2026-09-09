package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.{IO, Ref}

import scala.concurrent.duration.Duration

import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.SimpleIOSuite

object EventTriggerGuardSuite extends SimpleIOSuite {

  private val logger = Slf4jLogger.getLogger[IO]

  private def evaluate(
    trigger: Option[IO[Unit]],
    participants: Int,
    pending: Int,
    threshold: Int,
    lastTrigger: Long = 0L
  ): IO[EventTriggerGuard.Decision] =
    Ref.of[IO, Long](lastTrigger).flatMap { lastTriggerRef =>
      EventTriggerGuard.evaluate(
        trigger,
        IO.pure(participants),
        lastTriggerRef,
        logger,
        pending,
        threshold,
        Duration.Zero
      )
    }

  test("explicit trigger intent below the batch threshold does not fire") {
    for {
      fired <- Ref.of[IO, Int](0)
      decision <- evaluate(Some(fired.update(_ + 1)), participants = 3, pending = 8, threshold = 9)
      count <- fired.get
    } yield expect.same(EventTriggerGuard.Decision.BelowThreshold, decision) && expect.same(0, count)
  }

  test("sufficient explicit trigger intent fires exactly once") {
    for {
      fired <- Ref.of[IO, Int](0)
      decision <- evaluate(Some(fired.update(_ + 1)), participants = 3, pending = 9, threshold = 9)
      count <- fired.get
    } yield expect.same(EventTriggerGuard.Decision.Fired, decision) && expect.same(1, count)
  }

  test("the participant guard remains independent of trigger-intent volume") {
    evaluate(Some(IO.unit), participants = 1, pending = 100, threshold = 9).map { decision =>
      expect.same(EventTriggerGuard.Decision.InsufficientParticipants, decision)
    }
  }

  test("an unwired trigger reports disabled without evaluating work") {
    evaluate(None, participants = 3, pending = 9, threshold = 9).map { decision =>
      expect.same(EventTriggerGuard.Decision.Disabled, decision)
    }
  }
}
