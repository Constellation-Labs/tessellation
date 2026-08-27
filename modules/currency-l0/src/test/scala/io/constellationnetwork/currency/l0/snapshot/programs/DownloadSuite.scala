package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.{IO, Ref}

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object DownloadSuite extends SimpleIOSuite {

  private val ordinal = SnapshotOrdinal.unsafeApply(17L)

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

  test("initial persistence restores certified state before advancing the snapshot head") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- Download.persistInitial(
        ordinal,
        record(events, "certified-state"),
        record(events, "snapshot-head").as(true),
        record(events, "clear-mempool")
      )
      observed <- events.get
    } yield expect.same(Vector("certified-state", "snapshot-head", "clear-mempool"), observed)
  }

  test("initial persistence fails closed before snapshot or mempool mutation when certified state is unavailable") {
    val unavailable = new RuntimeException("certified state unavailable")

    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- Download
        .persistInitial(
          ordinal,
          record(events, "certified-state").flatMap(_ => IO.raiseError[Unit](unavailable)),
          record(events, "snapshot-head").as(true),
          record(events, "clear-mempool")
        )
        .attempt
      observed <- events.get
    } yield expect.same(Vector("certified-state"), observed).and(expect.same(Left(unavailable), result))
  }
}
