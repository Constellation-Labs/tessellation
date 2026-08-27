package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.{IO, Ref}

import weaver.SimpleIOSuite

object DownloadSuite extends SimpleIOSuite {

  private def record(events: Ref[IO, Vector[String]], value: String): IO[Unit] =
    events.update(_ :+ value)

  test("initial download keeps sequential snapshot persistence") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- Download.selectPersistence(
        recovery = false,
        sequential = record(events, "sequential-prepend"),
        recoveryReset = record(events, "recovery-reset")
      )
      observed <- events.get
    } yield expect.same(Vector("sequential-prepend"), observed)
  }

  test("follower recovery selects the non-contiguous recovery reset") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- Download.selectPersistence(
        recovery = true,
        sequential = record(events, "sequential-prepend"),
        recoveryReset = record(events, "recovery-reset")
      )
      observed <- events.get
    } yield expect.same(Vector("recovery-reset"), observed)
  }
}
