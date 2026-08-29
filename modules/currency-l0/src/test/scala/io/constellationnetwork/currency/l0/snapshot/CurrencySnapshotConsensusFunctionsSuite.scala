package io.constellationnetwork.currency.l0.snapshot

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

object CurrencySnapshotConsensusFunctionsSuite extends SimpleIOSuite {

  private val ordinal = SnapshotOrdinal.unsafeApply(354L)
  private val expectedHash = Hash("expected")
  private val view = GlobalSyncView(ordinal, expectedHash, EpochProgress.MinValue)

  test("proposal fee context retention accepts the exact selected Global identity") {
    for {
      retained <- Ref.of[IO, Vector[SnapshotOrdinal]](Vector.empty)
      _ <- CurrencySnapshotConsensusFunctions.retainExactGlobalFeeContext[IO](
        view.some,
        requested => retained.update(_ :+ requested).as(expectedHash.some)
      )
      observed <- retained.get
    } yield expect.same(Vector(ordinal), observed)
  }

  test("proposal fee context retention fails when the selected Global state is unavailable") {
    CurrencySnapshotConsensusFunctions
      .retainExactGlobalFeeContext[IO](view.some, _ => IO.pure(none))
      .attempt
      .map(result => expect(result == Left(CurrencySnapshotConsensusFunctions.ExactGlobalFeeContextUnavailable(ordinal))))
  }

  test("proposal fee context retention fails on a same-ordinal Global hash conflict") {
    val actualHash = Hash("conflicting")

    CurrencySnapshotConsensusFunctions
      .retainExactGlobalFeeContext[IO](view.some, _ => IO.pure(actualHash.some))
      .attempt
      .map(result =>
        expect(
          result == Left(
            CurrencySnapshotConsensusFunctions.ExactGlobalFeeContextHashMismatch(ordinal, expectedHash, actualHash)
          )
        )
      )
  }

  test("legacy proposal without a Global sync view does not request retention") {
    for {
      calls <- Ref.of[IO, Int](0)
      _ <- CurrencySnapshotConsensusFunctions.retainExactGlobalFeeContext[IO](
        none,
        _ => calls.update(_ + 1).as(expectedHash.some)
      )
      observed <- calls.get
    } yield expect.same(0, observed)
  }
}
