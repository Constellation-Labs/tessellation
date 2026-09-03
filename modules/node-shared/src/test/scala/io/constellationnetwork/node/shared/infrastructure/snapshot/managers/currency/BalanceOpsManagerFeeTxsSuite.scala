package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator.{
  FeeTransactionValidationErrorOr,
  SameSourceAndDestinationAddress
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.BalanceOpsManager.applyFeeTransactions
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

object BalanceOpsManagerFeeTxsSuite extends SimpleIOSuite with Checkers {

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

  /** Rejects transactions whose source and destination match, which is enough to exercise both selection modes without a SecurityProvider:
    * the real validator's verdicts are also a pure function of the transaction.
    */
  private val stubValidator: FeeTransactionValidator[IO] = new FeeTransactionValidator[IO] {
    private def verdict(tx: Signed[FeeTransaction]): FeeTransactionValidationErrorOr[Signed[FeeTransaction]] =
      if (tx.value.source =!= tx.value.destination) tx.validNec
      else SameSourceAndDestinationAddress(tx.value.source).invalidNec

    def validate(
      signedTransaction: Signed[FeeTransaction],
      enforceWalletAuthorization: Boolean
    ): IO[FeeTransactionValidationErrorOr[Signed[FeeTransaction]]] = verdict(signedTransaction).pure[IO]

    def validate(
      signedTransactions: NonEmptyList[Signed[FeeTransaction]],
      enforceWalletAuthorization: Boolean
    ): IO[FeeTransactionValidationErrorOr[NonEmptyList[Signed[FeeTransaction]]]] =
      signedTransactions.traverse(verdict).pure[IO]
  }

  private val balanceOps = new BalanceOpsManager[IO](stubValidator)

  private val validTx = feeTx(payer, payeeA, 10L, 1)
  private val otherValidTx = feeTx(payer, payeeB, 20L, 2)
  private val selfPayingTx = feeTx(payer, payer, 30L, 3)

  test("at or above the activation ordinal, an invalid fee transaction is dropped and the rest are applied") {
    // One invalid entry must not decide the fate of the whole set: the valid transactions alongside it are
    // still applied, and only the invalid one is left out.
    val txs = SortedSet(validTx, otherValidTx, selfPayingTx)

    balanceOps.validateFeeTxs(txs.some, enforceWalletAuthorization = true, atOrAboveActivationOrdinal = true).map { result =>
      expect.all(
        result.contains(SortedSet(validTx, otherValidTx)),
        result.forall(!_.contains(selfPayingTx))
      )
    }
  }

  test("at or above the activation ordinal, an all-invalid set yields an empty set rather than raising") {
    balanceOps
      .validateFeeTxs(SortedSet(selfPayingTx).some, enforceWalletAuthorization = true, atOrAboveActivationOrdinal = true)
      .attempt
      .map(result => expect(result == Right(Some(SortedSet.empty[Signed[FeeTransaction]]))))
  }

  test("at or above the activation ordinal, an all-valid set is returned unchanged") {
    val txs = SortedSet(validTx, otherValidTx)

    balanceOps
      .validateFeeTxs(txs.some, enforceWalletAuthorization = true, atOrAboveActivationOrdinal = true)
      .map(result => expect(result.contains(txs)))
  }

  test("below the activation ordinal, an invalid fee transaction still raises") {
    // Deliberate: selecting per transaction changes which artifact the same events produce, and below the
    // gate the data application layer applies its earlier rules, so a mixed fleet would disagree during a
    // rolling upgrade.
    balanceOps
      .validateFeeTxs(SortedSet(validTx, selfPayingTx).some, enforceWalletAuthorization = false, atOrAboveActivationOrdinal = false)
      .attempt
      .map(result => expect(result.isLeft))
  }

  test("below the activation ordinal, an all-valid set is returned unchanged") {
    val txs = SortedSet(validTx, otherValidTx)

    balanceOps
      .validateFeeTxs(txs.some, enforceWalletAuthorization = false, atOrAboveActivationOrdinal = false)
      .map(result => expect(result.contains(txs)))
  }

  test("no fee transactions is a no-op in both modes") {
    for {
      above <- balanceOps.validateFeeTxs(none, enforceWalletAuthorization = true, atOrAboveActivationOrdinal = true)
      below <- balanceOps.validateFeeTxs(none, enforceWalletAuthorization = false, atOrAboveActivationOrdinal = false)
    } yield expect.all(above.isEmpty, below.isEmpty)
  }
}
