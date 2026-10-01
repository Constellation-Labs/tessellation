package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, BalanceArithmeticError}
import io.constellationnetwork.schema.delegatedStake.PendingDelegatedStakeWithdrawal
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Ephemeral settlement shared by reward payout and principal unlocking; this is not part of the snapshot encoding. The original expired
  * map must still be used for pending-state cleanup in every original address bucket. See
  * docs/operations/delegated-stake-settlement-audit.md for the required pre-activation lineage audit and the maximum-entitlement policy.
  */
case class DelegatedStakeWithdrawalSettlement(
  withdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
  rewardsByAddress: SortedMap[Address, Amount],
  duplicateCount: Int,
  orphanCount: Int
)

object DelegatedStakeWithdrawalSettlement {

  def prepare(
    expiredWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    unexpiredWithdrawals: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    activeTokenLocksByRef: Map[Hash, Signed[TokenLock]],
    ordinal: SnapshotOrdinal,
    activationOrdinal: SnapshotOrdinal
  ): Either[BalanceArithmeticError, Option[DelegatedStakeWithdrawalSettlement]] =
    if (ordinal < activationOrdinal) Right(None)
    else {
      val canonicalOrder = implicitly[Ordering[PendingDelegatedStakeWithdrawal]]
      val dueRefs = expiredWithdrawals.valuesIterator.flatten.map(_.event.tokenLockRef).toSet
      // Payout selection and cleanup must cover the same family, including later-cooldown copies.
      val records = (expiredWithdrawals.valuesIterator.flatten ++ unexpiredWithdrawals.valuesIterator.flatten)
        .filter(w => dueRefs(w.event.tokenLockRef))
        .toList
      val (eligible, orphans) = records.partition(w => activeTokenLocksByRef.contains(w.event.tokenLockRef))
      val selected = eligible
        .groupBy(_.event.tokenLockRef)
        .valuesIterator
        .map(
          _.reduceLeft { (left, right) =>
            // Max is the policy for audited overlapping cumulative lineage, not proof of arbitrary corrupt entitlements.
            // An ordinal/hash-pinned lineage audit is required before public activation; duplicates are never added together.
            val rewardComparison = java.lang.Long.compare(left.rewards.value.value, right.rewards.value.value)
            val recordComparison = canonicalOrder.compare(left, right)
            // Pending-record ordering omits acceptedOrdinal; complete the tie-break for records from different buckets.
            val canonicalLeft = recordComparison < 0 || recordComparison == 0 && left.acceptedOrdinal <= right.acceptedOrdinal
            if (rewardComparison > 0 || rewardComparison == 0 && canonicalLeft) left else right
          }
        )
        .toList
      val withdrawals = SortedMap.from(
        selected
          // Both the map key and the signed stake source can be malformed; the active lock defines the owner.
          .groupBy(w => activeTokenLocksByRef(w.event.tokenLockRef).source)
          .view
          .mapValues(SortedSet.from(_))
      )

      withdrawals.toList.traverse {
        case (owner, records) =>
          records.toList.foldM(Amount.empty)((total, record) => total.plus(record.rewards)).map(owner -> _)
      }.map { rewards =>
        Some(DelegatedStakeWithdrawalSettlement(withdrawals, SortedMap.from(rewards), eligible.size - selected.size, orphans.size))
      }
    }
}
