package io.constellationnetwork.currency.l0.snapshot.services

import cats.effect.{IO, Ref}
import cats.syntax.all._

import weaver.SimpleIOSuite

object StateChannelSnapshotServiceSuite extends SimpleIOSuite {

  test("a rejected snapshot does not run data-application or binary-enqueue effects") {
    for {
      accepted <- Ref.of[IO, Int](0)
      rejected <- Ref.of[IO, Int](0)
      result <- StateChannelSnapshotService.continueAfterPersist(
        persisted = false,
        accepted.update(_ + 1),
        rejected.update(_ + 1)
      )
      acceptedCount <- accepted.get
      rejectedCount <- rejected.get
    } yield expect(!result) && expect.same(0, acceptedCount) && expect.same(1, rejectedCount)
  }

  test("an accepted snapshot runs finalize-time effects and propagates their failure") {
    val failure = new RuntimeException("binary enqueue failed")

    StateChannelSnapshotService
      .continueAfterPersist[IO](
        persisted = true,
        failure.raiseError[IO, Unit],
        IO.raiseError(new AssertionError("rejection branch must not run"))
      )
      .attempt
      .map(result => expect.same(Left(failure), result))
  }

  test("recovery publication commits durability, special receipt, then ordinary outbox") {
    for {
      order <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- StateChannelSnapshotService.commitPreparedPublications(
        recoveryRequired = true,
        ensureRecoveryArtifactDurable = order.update(_ :+ "durable"),
        markRecoveryLocallyCommitted = order.update(_ :+ "recovery"),
        markOrdinaryLocallyCommitted = order.update(_ :+ "ordinary")
      )
      observed <- order.get
    } yield expect.same(Vector("durable", "recovery", "ordinary"), observed)
  }

  test("a failure before the recovery receipt commits never makes the ordinary outbox publishable") {
    val failure = new RuntimeException("durable read-back failed")

    for {
      order <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- StateChannelSnapshotService
        .commitPreparedPublications(
          recoveryRequired = true,
          ensureRecoveryArtifactDurable = order.update(_ :+ "durable") >> failure.raiseError[IO, Unit],
          markRecoveryLocallyCommitted = order.update(_ :+ "recovery"),
          markOrdinaryLocallyCommitted = order.update(_ :+ "ordinary")
        )
        .attempt
      observed <- order.get
    } yield expect.same(Left(failure), result) && expect.same(Vector("durable"), observed)
  }

  test("without a recovery refresh only the ordinary outbox is committed") {
    for {
      order <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- StateChannelSnapshotService.commitPreparedPublications(
        recoveryRequired = false,
        ensureRecoveryArtifactDurable = order.update(_ :+ "unexpected-durable"),
        markRecoveryLocallyCommitted = order.update(_ :+ "unexpected-recovery"),
        markOrdinaryLocallyCommitted = order.update(_ :+ "ordinary")
      )
      observed <- order.get
    } yield expect.same(Vector("ordinary"), observed)
  }
}
