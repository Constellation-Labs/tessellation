package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencySnapshotAcceptanceManager.{
  applyFeeTransactions,
  applyFeeTransactionsUnchecked
}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object CurrencySnapshotAcceptanceManagerFeeTxsSuite extends SimpleIOSuite with Checkers {

  private val payer = Address("DAG011jH7FMDvKpdb7wewrMWwYtkwq56nHquAHdi")
  private val payeeA = Address("DAG06z64ifT2HzXoHfMexRfrcnpYFEwMqjFiPKze")
  private val payeeB = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")
  private val payeeC = Address("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM")
  private val payeeD = Address("DAG0eQr94qUQSUhmYGNXt6CoBKWu5K6htvRMGC6M")

  /** 4_611_686_018_427_387_904 — four of these sum to exactly 2^64, wrapping a Long back to zero. */
  private val twoPow62 = 4611686018427387904L

  private def proofOf(seed: Int): NonEmptySet[SignatureProof] = {
    val hex = (seed.toString * 128).take(128)
    NonEmptySet.one(SignatureProof(Id(Hex(hex)), Signature(Hex(hex))))
  }

  private def feeTx(source: Address, destination: Address, amount: Long, seed: Int): Signed[FeeTransaction] =
    Signed(
      FeeTransaction(source, destination, Amount(NonNegLong.unsafeFrom(amount)), Hash.empty),
      proofOf(seed)
    )

  private def balancesOf(entries: (Address, Long)*): SortedMap[Address, Balance] =
    SortedMap.from(entries.map { case (a, v) => a -> Balance(NonNegLong.unsafeFrom(v)) })

  private def totalSupply(balances: SortedMap[Address, Balance]): BigInt =
    balances.values.foldLeft(BigInt(0))((acc, b) => acc + BigInt(b.value.value))

  pureTest("rejects a batch that overflows the source balance and creates no supply") {
    // Four transfers of 2^62 out of an account holding nothing. Under wrapping arithmetic the source
    // walks 0 -> -2^62 -> Long.MinValue -> +2^62 -> 0, ending non-negative, so a guard that only
    // inspects the final total sees nothing wrong while every destination gains 2^62 from nowhere.
    val txs = SortedSet(
      feeTx(payer, payeeA, twoPow62, 1),
      feeTx(payer, payeeB, twoPow62, 2),
      feeTx(payer, payeeC, twoPow62, 3),
      feeTx(payer, payeeD, twoPow62, 4)
    )
    val before = balancesOf(payer -> 0L)

    val (after, accepted, rejected) = applyFeeTransactions(before, txs)

    expect.all(
      accepted.isEmpty,
      rejected.size == 4,
      after.get(payeeA).forall(_ == Balance.empty),
      after.get(payeeB).forall(_ == Balance.empty),
      after.get(payeeC).forall(_ == Balance.empty),
      after.get(payeeD).forall(_ == Balance.empty),
      after.getOrElse(payer, Balance.empty) == Balance.empty,
      totalSupply(after) == BigInt(0)
    )
  }

  pureTest("rejects a single fee transaction the source cannot afford") {
    val txs = SortedSet(feeTx(payer, payeeA, 101L, 1))
    val before = balancesOf(payer -> 100L)

    val (after, accepted, rejected) = applyFeeTransactions(before, txs)

    expect.all(
      accepted.isEmpty,
      rejected.size == 1,
      after.getOrElse(payer, Balance.empty).value.value == 100L,
      after.get(payeeA).forall(_ == Balance.empty)
    )
  }

  pureTest("applies an affordable fee transaction") {
    val txs = SortedSet(feeTx(payer, payeeA, 40L, 1))
    val before = balancesOf(payer -> 100L, payeeA -> 5L)

    val (after, accepted, rejected) = applyFeeTransactions(before, txs)

    expect.all(
      accepted.size == 1,
      rejected.isEmpty,
      after(payer).value.value == 60L,
      after(payeeA).value.value == 45L,
      totalSupply(after) == totalSupply(before)
    )
  }

  pureTest("debits between transactions instead of validating against a stale balance") {
    // Each transaction is affordable against the starting balance of 100, but not against the
    // balance left after the previous one. Only the first may be accepted.
    val txs = SortedSet(
      feeTx(payer, payeeA, 60L, 1),
      feeTx(payer, payeeB, 60L, 2)
    )
    val before = balancesOf(payer -> 100L)

    val (after, accepted, rejected) = applyFeeTransactions(before, txs)

    expect.all(
      accepted.size == 1,
      rejected.size == 1,
      after(payer).value.value == 40L,
      totalSupply(after) == totalSupply(before)
    )
  }

  pureTest("rejects a transfer that would overflow the destination balance") {
    val txs = SortedSet(feeTx(payer, payeeA, 10L, 1))
    val before = balancesOf(payer -> 10L, payeeA -> (Long.MaxValue - 5L))

    val (after, accepted, rejected) = applyFeeTransactions(before, txs)

    expect.all(
      accepted.isEmpty,
      rejected.size == 1,
      after(payeeA).value.value == Long.MaxValue - 5L,
      totalSupply(after) == totalSupply(before)
    )
  }

  pureTest("accepts the affordable prefix of a batch and rejects the rest") {
    val txs = SortedSet(
      feeTx(payer, payeeA, 30L, 1),
      feeTx(payer, payeeB, 40L, 2),
      feeTx(payer, payeeC, 90L, 3)
    )
    val before = balancesOf(payer -> 100L)

    val (after, accepted, rejected) = applyFeeTransactions(before, txs)

    expect.all(
      accepted.size == 2,
      rejected.size == 1,
      rejected.head.value.amount.value.value == 90L,
      after(payer).value.value == 30L,
      totalSupply(after) == totalSupply(before)
    )
  }

  test("never changes total supply, for any batch of fee transactions") {
    val gen = for {
      startingBalance <- Gen.oneOf(0L, 1L, 100L, Long.MaxValue / 2, Long.MaxValue)
      amounts <- Gen.listOfN(4, Gen.oneOf(0L, 1L, 100L, twoPow62, Long.MaxValue))
    } yield (startingBalance, amounts)

    forall(gen) {
      case (startingBalance, amounts) =>
        val destinations = List(payeeA, payeeB, payeeC, payeeD)
        val txs = SortedSet.from(amounts.zip(destinations).zipWithIndex.map {
          case ((amount, destination), i) => feeTx(payer, destination, amount, i + 1)
        })
        val before = balancesOf(payer -> startingBalance)

        val (after, _, _) = applyFeeTransactions(before, txs)

        expect.eql(totalSupply(before), totalSupply(after))
    }
  }

  pureTest("the unchecked path still reproduces the mint, so gated replay matches the signed history") {
    // Kept deliberately. The fix changes what a snapshot contains, so a node recomputing an ordinal from
    // before the activation with checked arithmetic would reach different balances than the snapshot that
    // was actually signed. This asserts the legacy path still wraps exactly as it did on 2026-08-24:
    // 0 -> -2^62 -> Long.MinValue -> +2^62 -> 0, crediting four destinations 2^62 each from nothing.
    val txs = SortedSet(
      feeTx(payer, payeeA, twoPow62, 1),
      feeTx(payer, payeeB, twoPow62, 2),
      feeTx(payer, payeeC, twoPow62, 3),
      feeTx(payer, payeeD, twoPow62, 4)
    )
    val before = balancesOf(payer -> 0L)

    val (after, accepted, rejected) = applyFeeTransactionsUnchecked(before, txs)

    expect.all(
      accepted.size == 4,
      rejected.isEmpty,
      after(payer) == Balance.empty,
      after(payeeA).value.value == twoPow62,
      after(payeeB).value.value == twoPow62,
      after(payeeC).value.value == twoPow62,
      after(payeeD).value.value == twoPow62,
      // supply created out of nothing -- the bug, preserved on purpose for pre-activation replay
      totalSupply(after) == BigInt(4) * BigInt(twoPow62)
    )
  }
}
