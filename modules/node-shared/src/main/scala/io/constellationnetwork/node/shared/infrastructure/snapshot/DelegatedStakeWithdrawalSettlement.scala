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

/** Ephemeral, not encoded in snapshots. The same settlement controls rewards, principal eligibility and pending cleanup. */
case class DelegatedStakeWithdrawalSettlement(
  withdrawalsByRef: SortedMap[Hash, PendingDelegatedStakeWithdrawal],
  rewardsByAddress: SortedMap[Address, Amount],
  duplicateCount: Int
) {
  def removeSettled(
    pending: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
  ): SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]] =
    pending.map { case (address, records) => address -> records.filterNot(w => withdrawalsByRef.contains(w.tokenLockRef)) }
      .filter(_._2.nonEmpty)
}

object DelegatedStakeWithdrawalSettlement {

  /** A due, backed lock settles once. Include every pending copy that cleanup will retire, even one with a later cooldown, so its greater
    * cumulative entitlement is not silently discarded. Max is the policy for overlapping cumulative lineage, NOT proof that arbitrary
    * corrupt records are entitled to that amount. Public activation requires an ordinal/hash-pinned lineage audit; see the activation docs.
    * Missing references remain pending on develop, including NEW replacements which first become last-active at R+1.
    */
  def prepare(
    expired: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    unexpired: SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    activeLocks: Map[Hash, Signed[TokenLock]],
    ordinal: SnapshotOrdinal,
    activation: SnapshotOrdinal
  ): Either[BalanceArithmeticError, Option[DelegatedStakeWithdrawalSettlement]] =
    if (ordinal < activation) Right(None)
    else {
      val dueRefs = expired.valuesIterator.flatten.map(_.tokenLockRef).filter(activeLocks.contains).toSet
      val records = (expired.valuesIterator.flatten ++ unexpired.valuesIterator.flatten).filter(w => dueRefs(w.tokenLockRef)).toList
      val canonicalOrder = implicitly[Ordering[PendingDelegatedStakeWithdrawal]]
      val selected = SortedMap.from(records.groupBy(_.tokenLockRef).map {
        case (ref, copies) =>
          ref -> copies.reduceLeft { (left, right) =>
            val rewardComparison = java.lang.Long.compare(left.rewards.value.value, right.rewards.value.value)
            val recordComparison = canonicalOrder.compare(left, right)
            // The schema ordering omits these metadata fields. Complete the local tie-break without changing any signed encoding.
            val metadataOrder = Ordering.Tuple3[SnapshotOrdinal, Option[Hash], Option[Long]]
            val metadataComparison = metadataOrder.compare(
              (left.acceptedOrdinal, left.currentTokenLockRef, left.currentAmount.map(_.value.value)),
              (right.acceptedOrdinal, right.currentTokenLockRef, right.currentAmount.map(_.value.value))
            )
            if (rewardComparison > 0 || rewardComparison == 0 && (recordComparison < 0 || recordComparison == 0 && metadataComparison <= 0))
              left
            else right
          }
      })
      // Keep identity keyed by lock hash: the schema SortedSet ordering can equate records with distinct currentTokenLockRef values.
      selected.toList
        .foldM(SortedMap.empty[Address, Amount]) {
          case (totals, (ref, record)) =>
            val owner = activeLocks(ref).source
            totals.getOrElse(owner, Amount.empty).plus(record.rewards).map(total => totals.updated(owner, total))
        }
        .map(totals => Some(DelegatedStakeWithdrawalSettlement(selected, totals, records.size - selected.size)))
    }
}
