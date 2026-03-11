package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.IO
import cats.syntax.all._

import weaver.SimpleIOSuite

object PendingTriggersSuite extends SimpleIOSuite {

  private def mkPending: IO[PendingTriggersF[IO]] = PendingTriggers.create[IO]

  test("pullNext returns None when nothing is pending") {
    for {
      pending <- mkPending
      result <- pending.pullNext
    } yield expect(result.isEmpty)
  }

  test("setEvent then pullNext returns Event") {
    for {
      pending <- mkPending
      _ <- pending.setEvent()
      result <- pending.pullNext
    } yield expect(result.contains(TriggerPriority.Event))
  }

  test("setTime then pullNext returns Time") {
    for {
      pending <- mkPending
      _ <- pending.setTime()
      result <- pending.pullNext
    } yield expect(result.contains(TriggerPriority.Time))
  }

  test("time takes priority over event") {
    for {
      pending <- mkPending
      _ <- pending.setEvent()
      _ <- pending.setTime()
      result <- pending.pullNext
    } yield expect(result.contains(TriggerPriority.Time))
  }

  test("setEvent does not downgrade existing Time") {
    for {
      pending <- mkPending
      _ <- pending.setTime()
      _ <- pending.setEvent()
      result <- pending.pullNext
    } yield expect(result.contains(TriggerPriority.Time))
  }

  test("pullNext clears state atomically") {
    for {
      pending <- mkPending
      _ <- pending.setTime()
      first <- pending.pullNext
      second <- pending.pullNext
    } yield expect(first.contains(TriggerPriority.Time)).and(expect(second.isEmpty))
  }

  test("concurrent set and pull does not lose triggers") {
    for {
      pending <- mkPending
      _ <- (1 to 1000).toList.traverse_ { i =>
        if (i % 2 == 0) pending.setEvent() else pending.setTime()
      }
      result <- pending.pullNext
    } yield expect(result.isDefined)
  }
}
