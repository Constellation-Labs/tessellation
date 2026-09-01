package io.constellationnetwork.dag.l0.infrastructure.snapshot.programs

import cats.effect.{IO, Ref}

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object RollbackLoaderSuite extends SimpleIOSuite {

  test("a failed recovery preflight never evaluates rollback mutation") {
    val rejected = new RuntimeException("rejected-plan")

    for {
      mutationRan <- Ref.of[IO, Boolean](false)
      result <- RollbackLoader
        .runPreflightThen[IO, Unit](IO.raiseError(rejected), mutationRan.set(true))
        .attempt
      ran <- mutationRan.get
    } yield expect.same(Left(rejected), result) && expect(!ran)
  }

  test("a successful recovery preflight runs before rollback mutation") {
    for {
      order <- Ref.of[IO, List[String]](List.empty)
      _ <- RollbackLoader.runPreflightThen[IO, Unit](
        order.update(_ :+ "preflight"),
        order.update(_ :+ "mutation")
      )
      observed <- order.get
    } yield expect.same(List("preflight", "mutation"), observed)
  }

  test("an unsupported full-snapshot recovery source fails before detailed validation or rollback loading") {
    val rejected = new RuntimeException("unsupported-full-snapshot")

    for {
      effects <- Ref.of[IO, List[String]](List.empty)
      result <- RollbackLoader
        .runSourcePreflightThen[IO, Unit](
          RollbackLoader.Source.FullSnapshot,
          Some {
            case RollbackLoader.Source.Incremental  => IO.unit
            case RollbackLoader.Source.FullSnapshot => IO.raiseError(rejected)
          },
          effects.update(_ :+ "synthetic-load") >>
            effects.update(_ :+ "detailed-validation") >>
            effects.update(_ :+ "rollback-mutation")
        )
        .attempt
      observed <- effects.get
    } yield expect.same(Left(rejected), result) && expect(observed.isEmpty)
  }

  test("ordinary certified rollback validation does not claim a legacy anchor") {
    val activation = SnapshotOrdinal.unsafeApply(100L)

    IO.pure(
      expect(!RollbackLoader.preLoadValidationRequired(SnapshotOrdinal.unsafeApply(99L), Some(activation))) &&
        expect(RollbackLoader.preLoadValidationRequired(activation, Some(activation))) &&
        expect(RollbackLoader.preLoadValidationRequired(SnapshotOrdinal.unsafeApply(101L), Some(activation)))
    )
  }

  test("explicit recovery validation claims every anchor") {
    IO.pure(expect(RollbackLoader.preLoadValidationRequired(SnapshotOrdinal.MinIncrementalValue, None)))
  }
}
