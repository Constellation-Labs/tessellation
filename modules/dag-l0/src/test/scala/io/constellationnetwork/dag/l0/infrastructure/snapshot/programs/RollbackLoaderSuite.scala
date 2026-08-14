package io.constellationnetwork.dag.l0.infrastructure.snapshot.programs

import cats.effect.{IO, Ref}

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
}
