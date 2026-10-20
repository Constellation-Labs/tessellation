package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.CurrencySnapshot
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral._
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object CurrentDagSupplySuite extends SimpleIOSuite with Checkers {
  private val owner = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val other = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")
  private val node = Id(Hex("1234567890abcdef"))
  private val proofs = NonEmptySet.one(SignatureProof(node, Signature(Hex(Hash.empty.value))))

  private def amount(value: Long): Amount = Amount(NonNegLong.unsafeFrom(value))
  private def balance(value: Long): Balance = Balance(NonNegLong.unsafeFrom(value))

  private def lock(value: Long): Signed[TokenLock] =
    Signed(TokenLock(owner, TokenLockAmount(PosLong.unsafeFrom(value)), TokenLockFee(0L), TokenLockReference.empty, None, None), proofs)

  private def escrow(value: Long): Signed[AllowSpend] =
    Signed(
      AllowSpend(
        owner,
        other,
        None,
        SwapAmount(PosLong.unsafeFrom(value)),
        AllowSpendFee(0L),
        AllowSpendReference.empty,
        EpochProgress(100L),
        List(other)
      ),
      proofs
    )

  private def stake(value: Long): Signed[UpdateDelegatedStake.Create] =
    Signed(
      UpdateDelegatedStake.Create(owner, node.toPeerId, DelegatedStakeAmount(NonNegLong.unsafeFrom(value)), tokenLockRef = Hash.empty),
      proofs
    )

  private def withBalance(value: Long): GlobalSnapshotInfo =
    GlobalSnapshotInfo.empty.copy(balances = SortedMap(owner -> balance(value)))

  pureTest("empty snapshots and missing optional buckets have zero supply") {
    val missing = GlobalSnapshotInfo.empty.copy(
      activeAllowSpends = None,
      activeTokenLocks = None,
      activeDelegatedStakes = None,
      delegatedStakesWithdrawals = None
    )
    expect(CurrentDagSupply.calculate(GlobalSnapshotInfo.empty) == Right(Amount.empty))
      .and(expect(CurrentDagSupply.calculate(missing) == Right(Amount.empty)))
  }

  pureTest("all native buckets contribute once, including multiple rewards at one address") {
    val info = GlobalSnapshotInfo.empty.copy(
      balances = SortedMap(owner -> balance(11L), other -> balance(13L)),
      activeTokenLocks = Some(SortedMap(owner -> SortedSet(lock(17L), lock(19L)))),
      activeAllowSpends = Some(SortedMap(None -> SortedMap(owner -> SortedSet(escrow(23L), escrow(29L))))),
      activeDelegatedStakes = Some(
        SortedMap(
          owner -> SortedSet(
            DelegatedStakeRecord(stake(17L), SnapshotOrdinal(1L), amount(31L)),
            DelegatedStakeRecord(stake(19L), SnapshotOrdinal(2L), amount(37L))
          )
        )
      ),
      delegatedStakesWithdrawals = Some(
        SortedMap(
          other -> SortedSet(
            PendingDelegatedStakeWithdrawal(stake(41L), amount(43L), SnapshotOrdinal(3L), EpochProgress(4L)),
            PendingDelegatedStakeWithdrawal(stake(47L), amount(53L), SnapshotOrdinal(5L), EpochProgress(6L))
          )
        )
      )
    )
    // delegated principal is already counted through the token locks, so stake amounts must not be added again
    expect(CurrentDagSupply.calculate(info) == Right(amount(276L)))
  }

  pureTest("metagraph escrow and mirrored token-lock balances are excluded") {
    val info = withBalance(7L).copy(
      lastCurrencySnapshots = SortedMap(
        other -> Left(Signed(CurrencySnapshot.mkGenesis(Map(owner -> balance(Long.MaxValue)), None, None), proofs))
      ),
      activeAllowSpends = Some(
        SortedMap(
          none[Address] -> SortedMap(owner -> SortedSet(escrow(11L))),
          other.some -> SortedMap(owner -> SortedSet(escrow(Long.MaxValue)))
        )
      ),
      tokenLockBalances = Some(SortedMap(other -> SortedMap(owner -> balance(Long.MaxValue))))
    )
    expect(CurrentDagSupply.calculate(info) == Right(amount(18L)))
  }

  pureTest("node collateral records do not duplicate locked principal") {
    val event = Signed(UpdateNodeCollateral.Create(owner, node.toPeerId, NodeCollateralAmount(100L), tokenLockRef = Hash.empty), proofs)
    val info = withBalance(25L).copy(
      activeTokenLocks = Some(SortedMap(owner -> SortedSet(lock(100L)))),
      activeNodeCollaterals = Some(SortedMap(owner -> SortedSet(NodeCollateralRecord(event, SnapshotOrdinal(1L))))),
      nodeCollateralWithdrawals = Some(
        SortedMap(owner -> SortedSet(PendingNodeCollateralWithdrawal(event, SnapshotOrdinal(2L), EpochProgress(3L))))
      )
    )
    expect(CurrentDagSupply.calculate(info) == Right(amount(125L)))
  }

  test("moving DAG from spendable balances into a token lock conserves supply") {
    forall(Gen.chooseNum(1L, 100000000000000000L)) { principal =>
      val unlocked = withBalance(principal + 100L)
      val locked = withBalance(100L).copy(activeTokenLocks = Some(SortedMap(owner -> SortedSet(lock(principal)))))
      expect(CurrentDagSupply.calculate(unlocked) == Right(amount(principal + 100L)))
        .and(expect(CurrentDagSupply.calculate(locked) == CurrentDagSupply.calculate(unlocked)))
    }
  }

  pureTest("withdrawal preserves accrued rewards through active, pending, and paid states") {
    val event = stake(100L)
    val active = withBalance(50L).copy(
      activeTokenLocks = Some(SortedMap(owner -> SortedSet(lock(100L)))),
      activeDelegatedStakes = Some(SortedMap(owner -> SortedSet(DelegatedStakeRecord(event, SnapshotOrdinal(1L), amount(20L)))))
    )
    val pending = active.copy(
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(
        SortedMap(owner -> SortedSet(PendingDelegatedStakeWithdrawal(event, amount(20L), SnapshotOrdinal(1L), EpochProgress(2L))))
      )
    )
    val paid = withBalance(170L)
    expect(CurrentDagSupply.calculate(active) == Right(amount(170L)))
      .and(expect(CurrentDagSupply.calculate(pending) == CurrentDagSupply.calculate(active)))
      .and(expect(CurrentDagSupply.calculate(paid) == CurrentDagSupply.calculate(active)))
  }

  pureTest("Long.MaxValue is accepted and the next datum is rejected without truncation") {
    val maximum = withBalance(Long.MaxValue)
    val overflow = maximum.copy(activeTokenLocks = Some(SortedMap(owner -> SortedSet(lock(1L)))))
    expect(CurrentDagSupply.calculate(maximum) == Right(amount(Long.MaxValue)))
      .and(expect(CurrentDagSupply.calculate(overflow) == Left(CurrentDagSupply.SupplyOverflow(BigInt(Long.MaxValue) + 1))))
  }

  pureTest("a sum that would wrap back to zero is rejected") {
    val quarter = 4611686018427387904L
    val overflowing = withBalance(quarter).copy(
      activeTokenLocks = Some(SortedMap(owner -> SortedSet(lock(quarter)))),
      activeAllowSpends = Some(SortedMap(None -> SortedMap(owner -> SortedSet(escrow(quarter))))),
      activeDelegatedStakes =
        Some(SortedMap(owner -> SortedSet(DelegatedStakeRecord(stake(quarter), SnapshotOrdinal(1L), amount(quarter)))))
    )
    expect(CurrentDagSupply.calculate(overflowing) == Left(CurrentDagSupply.SupplyOverflow(BigInt(quarter) * 4)))
  }
}
