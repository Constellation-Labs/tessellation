package io.constellationnetwork.currency.l0.snapshot.services

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage
import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage.{
  CurrencyFeeContextKey,
  CurrencyFeeContextReceipt
}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object StateChannelSnapshotServiceSuite extends SimpleIOSuite {

  private val key = CurrencyFeeContextKey(SnapshotOrdinal.unsafeApply(12L), Hash("artifact"))
  private val view = GlobalSyncView(SnapshotOrdinal.unsafeApply(90L), Hash("global"), EpochProgress.MinValue)
  private val stakingAddress = Address.fromBytes("staking".getBytes("UTF-8"))
  private val balance = Balance(NonNegLong.unsafeFrom(50L))
  private val receipt = CurrencyFeeContextReceipt(
    CurrencyFeeContextReceiptStorage.CurrentEncodingVersion,
    key.currencyOrdinal,
    key.currencyArtifactHash,
    view,
    stakingAddress.some,
    balance
  )

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

  test("the selected fee receipt is released only after the durable outbox commit") {
    for {
      order <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- StateChannelSnapshotService.commitPreparedPublicationsAndReleaseFeeContext(
        recoveryRequired = false,
        ensureRecoveryArtifactDurable = order.update(_ :+ "unexpected-durable"),
        markRecoveryLocallyCommitted = order.update(_ :+ "unexpected-recovery"),
        markOrdinaryLocallyCommitted = order.update(_ :+ "ordinary"),
        releaseFeeContext = order.update(_ :+ "release")
      )
      observed <- order.get
    } yield expect.same(Vector("ordinary", "release"), observed)
  }

  test("a failed outbox commit retains the selected fee receipt") {
    val failure = new RuntimeException("outbox commit failed")

    for {
      order <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- StateChannelSnapshotService
        .commitPreparedPublicationsAndReleaseFeeContext(
          recoveryRequired = false,
          ensureRecoveryArtifactDurable = IO.unit,
          markRecoveryLocallyCommitted = IO.unit,
          markOrdinaryLocallyCommitted = order.update(_ :+ "ordinary") >> failure.raiseError[IO, Unit],
          releaseFeeContext = order.update(_ :+ "release")
        )
        .attempt
      observed <- order.get
    } yield expect.same(Left(failure), result) && expect.same(Vector("ordinary"), observed)
  }

  test("binary fee input loads only from the exact selected receipt") {
    StateChannelSnapshotService
      .loadFeeContextBalance(key, view, stakingAddress.some, _ => receipt.some.pure[IO])
      .map(observed => expect.same(balance, observed))
  }

  test("a missing fee-context receipt fails before binary signing") {
    StateChannelSnapshotService
      .loadFeeContextBalance[IO](key, view, stakingAddress.some, _ => none.pure[IO])
      .attempt
      .map(result => expect(result.swap.exists(_.isInstanceOf[CurrencyFeeContextReceiptStorage.MissingCurrencyFeeContextReceipt])))
  }

  test("a mismatched fee-context receipt fails before binary signing") {
    val differentView = view.copy(hash = Hash("different-global"))

    StateChannelSnapshotService
      .loadFeeContextBalance[IO](key, differentView, stakingAddress.some, _ => receipt.some.pure[IO])
      .attempt
      .map(result => expect(result.swap.exists(_.isInstanceOf[StateChannelSnapshotService.CurrencyFeeContextReceiptMismatch])))
  }
}
