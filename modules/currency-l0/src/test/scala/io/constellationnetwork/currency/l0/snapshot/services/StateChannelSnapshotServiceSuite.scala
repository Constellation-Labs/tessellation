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
}
