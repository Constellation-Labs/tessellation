package io.constellationnetwork.currency.l0.snapshot.services

import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object StateChannelSnapshotServiceSuite extends SimpleIOSuite {

  private val requestedOrdinal = SnapshotOrdinal.unsafeApply(81L)
  private val stakingAddress = Address.fromBytes("staking-address".getBytes("UTF-8"))
  private val stakingBalance = Balance(NonNegLong.unsafeFrom(125L))

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

  test("state-channel fee input loads the staking balance from the exact agreed Global ordinal") {
    val info = GlobalSnapshotInfo.empty.copy(balances = SortedMap(stakingAddress -> stakingBalance))

    for {
      observed <- Ref.of[IO, Option[SnapshotOrdinal]](None)
      balance <- StateChannelSnapshotService.loadStakedBalance[IO](
        requestedOrdinal,
        stakingAddress,
        ordinal => observed.set(ordinal.some).as(info.some)
      )
      requested <- observed.get
    } yield expect.same(requestedOrdinal.some, requested).and(expect.same(stakingBalance, balance))
  }

  test("missing historical Global context fails closed instead of using a moving latest balance") {
    for {
      result <- StateChannelSnapshotService
        .loadStakedBalance[IO](requestedOrdinal, stakingAddress, _ => none[GlobalSnapshotInfo].pure[IO])
        .attempt
    } yield expect(result.isLeft)
  }
}
