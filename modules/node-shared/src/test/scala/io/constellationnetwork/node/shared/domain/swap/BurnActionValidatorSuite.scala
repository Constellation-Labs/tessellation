package io.constellationnetwork.node.shared.domain.swap

import cats.data.NonEmptyList

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.swap.BurnActionValidator._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BurnAction, BurnTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.{CurrencyId, SwapAmount}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

object BurnActionValidatorSuite extends SimpleIOSuite {
  private val owner = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val holder = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWC")
  private val initial = SortedMap(owner -> Balance(100L), holder -> Balance(100L))
  private def burn(amounts: Long*): BurnAction = BurnAction(
    NonEmptyList.fromListUnsafe(
      amounts.toList.map(amount => BurnTransaction(CurrencyId(owner), SwapAmount(PosLong.unsafeFrom(amount)), owner))
    )
  )
  private def run(actions: BurnAction*) =
    accept(SortedSet.from(actions), owner, initial, SnapshotOrdinal.MinValue, SnapshotOrdinal.MinValue)

  pureTest("full-balance self burn debits only the emitter") {
    val action = burn(100L)
    val result = run(action)
    expect.same(initial.updated(owner, Balance.empty), result.balances) &&
    expect.same(SortedSet(action), result.accepted) && expect(result.rejected.isEmpty)
  }

  List(9L -> false, 10L -> true, 11L -> true).foreach {
    case (ordinal, enabled) =>
      pureTest(s"first-active boundary $ordinal uses the signed parent Global ordinal") {
        val result = accept(SortedSet(burn(1L)), owner, initial, SnapshotOrdinal.unsafeApply(ordinal), SnapshotOrdinal.unsafeApply(10L))
        expect.same(enabled, result.accepted.nonEmpty) &&
        expect.same(if (enabled) Balance(99L) else Balance(100L), result.balances(owner))
      }
  }

  pureTest("disabled sentinel remains disabled even at MaxValue") {
    val result = accept(SortedSet(burn(1L)), owner, initial, SnapshotOrdinal.MaxValue, SnapshotOrdinal.MaxValue)
    expect.same(initial, result.balances) && expect.same(List(burn(1L) -> Disabled), result.rejected)
  }

  pureTest("different holder source cannot be burned") {
    val action = BurnAction(NonEmptyList.one(burn(1L).burnTransactions.head.copy(source = holder)))
    val result = run(action)
    expect.same(initial, result.balances) && expect.same(List(action -> InvalidSource), result.rejected)
  }

  pureTest("unapplied Global changes return an explicit rejection without touching balances") {
    val action = burn(1L)
    val result = accept(SortedSet(action), owner, initial, SnapshotOrdinal.MinValue, SnapshotOrdinal.MinValue, true)
    expect.same(initial, result.balances) && expect.same(List(action -> PendingGlobalChanges), result.rejected) &&
    expect(result.accepted.isEmpty)
  }

  pureTest("another native currency cannot be burned") {
    val action = BurnAction(NonEmptyList.one(burn(1L).burnTransactions.head.copy(currencyId = CurrencyId(holder))))
    val result = run(action)
    expect.same(initial, result.balances) && expect.same(List(action -> InvalidCurrency), result.rejected)
  }

  pureTest("an overdrawn action is rejected atomically, not partly applied") {
    val result = run(burn(60L, 41L))
    expect.same(initial, result.balances) && expect(result.accepted.isEmpty) && expect(result.rejected.nonEmpty)
  }

  pureTest("actions share a cumulative balance in canonical order") {
    val result = run(burn(60L), burn(50L))
    expect.same(Balance(50L), result.balances(owner)) && expect.same(SortedSet(burn(50L)), result.accepted) &&
    expect.same(1, result.rejected.size) && expect.same(result, run(burn(50L), burn(60L)))
  }

  pureTest("equal-amount legitimate burns inside one action remain distinct") {
    val result = run(burn(50L, 50L))
    expect.same(Balance.empty, result.balances(owner)) && expect(result.rejected.isEmpty)
  }

  pureTest("missing or exhausted balance rejects without a new balance row") {
    val result = accept(SortedSet(burn(1L)), owner, SortedMap.empty, SnapshotOrdinal.MinValue, SnapshotOrdinal.MinValue)
    expect(result.balances.isEmpty) && expect(result.accepted.isEmpty) && expect(result.rejected.nonEmpty)
  }

  pureTest("Long.MaxValue can be burned exactly; cumulative overflow cannot wrap") {
    val starting = SortedMap(owner -> Balance(Long.MaxValue))
    val full = accept(SortedSet(burn(Long.MaxValue)), owner, starting, SnapshotOrdinal.MinValue, SnapshotOrdinal.MinValue)
    val overflow = accept(SortedSet(burn(Long.MaxValue, 1L)), owner, starting, SnapshotOrdinal.MinValue, SnapshotOrdinal.MinValue)
    expect.same(Balance.empty, full.balances(owner)) && expect(full.rejected.isEmpty) &&
    expect.same(starting, overflow.balances) && expect(overflow.accepted.isEmpty)
  }
}
