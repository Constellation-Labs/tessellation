package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.syntax.eq._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.DustSweep
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}

import eu.timepit.refined.types.numeric.NonNegLong

/** Deterministic, ordinal-gated, consensus-critical one-time GSI dust sweep (state deflation).
  *
  * The testnet global state is dominated by a deliberately-injected dust population (hundreds of thousands of addresses each holding
  * exactly 12345 datum, all pure receivers with empty transaction refs). This object removes that sub-threshold liquid dust at a single
  * coordinated ordinal during a network-wide cold restart, collapsing the state from ~80MB to ~1MB.
  *
  * '''This is consensus-critical.''' Every honest node MUST compute the identical swept `GlobalSnapshotInfo` and the identical MPT state
  * root at the sweep ordinal, or the cluster forks. The transform is a pure function of the GSI map contents at a fixed ordinal (sorted
  * maps, commutative datum sum), so every node at the sweep ordinal computes the identical pruned GSI and root. The gating, threshold, and
  * burn-vs-treasury choice come from the per-environment compile-time `dustSweeps` config literal (NOT HOCON): the jar hash plus the
  * environment is the determinism fence.
  *
  * Safety gates (an address is swept only if ALL hold):
  *
  *   1. ORDINAL GATE: the sweep fires only when `dustSweeps.get(env).flatMap(_.get(ordinal))` returns a `DustSweep` (exact-key lookup). An
  *      entry fires exactly once at its ordinal and never replays; an absent environment never sweeps.
  *
  * 2. DUST THRESHOLD: only an address with `balance.value <= threshold.value` is eligible.
  *
  * 3. EMPTY-REF GATE: only an address whose `lastTxRef` is absent or empty (`TransactionReference.empty`, ordinal 0) is eligible. An
  * address that ever SENT has a non-empty ref; pruning it would reset its nonce and reopen a transaction-replay vector. The dust population
  * is entirely pure receivers (empty refs), so this gate loses zero coverage.
  *
  * 4. COMPLETE EXCLUSION: an address that appears as a key (INCLUDING nested inner-map keys) in ANY non-balance Address-keyed
  * `GlobalSnapshotInfo` field is never swept. Locking/staking/collateralizing debits the liquid `balances` entry, so a real staker can
  * legitimately sit near zero liquid balance; sweeping their dust would be wrong. See `addressesWithNonBalanceState`.
  *
  * 5. NOT THE COLLECTION ADDRESS: the treasury sink itself is never a sweep candidate.
  */
object GlobalSnapshotDustSweep {

  /** Union of every address that appears as a key in any NON-balance Address-keyed field of the GSI, including nested inner-map keys.
    *
    * These addresses are excluded from the sweep. The set references EVERY Address-keyed field other than `balances` (the field being
    * swept). A reflective coverage test (`GlobalSnapshotDustSweepSuite`) mechanically fails if a future Address-keyed field is added but
    * not referenced here.
    *
    * Address-keyed fields covered (13 total):
    *   - lastStateChannelSnapshotHashes (Address keys)
    *   - lastCurrencySnapshots (Address keys)
    *   - lastCurrencySnapshotsProofs (Address keys)
    *   - activeAllowSpends (outer Option[Address] keys AND inner Address keys)
    *   - activeTokenLocks (Address keys)
    *   - tokenLockBalances (outer Address keys AND inner Address keys)
    *   - lastAllowSpendRefs (Address keys)
    *   - lastTokenLockRefs (Address keys)
    *   - activeDelegatedStakes (Address keys)
    *   - delegatedStakesWithdrawals (Address keys)
    *   - activeNodeCollaterals (Address keys)
    *   - nodeCollateralWithdrawals (Address keys)
    *   - metagraphSyncData (Address keys)
    *
    * `lastTxRefs` is Address-keyed but is DELIBERATELY EXCLUDED from this protected set. Every pure receiver (the entire dust population)
    * holds an empty-ref `lastTxRefs` entry, so treating `lastTxRefs` keys as protected would exclude the whole dust population and the
    * sweep would remove nothing (verified live: ~444k of ~444.5k `lastTxRefs` entries are empty). The transaction-nonce / replay concern is
    * instead handled by the EMPTY-REF GATE in `applyDustSweep` (sweep only absent/empty refs; a never-sent address has no prior transaction
    * to replay).
    *
    * Fields intentionally NOT included because they are not Address-keyed: `updateNodeParameters` (Id keys) and `priceState` (TokenPair
    * keys).
    */
  def addressesWithNonBalanceState(gsi: GlobalSnapshotInfo): Set[Address] = {
    def outerKeys[V](m: SortedMap[Address, V]): Set[Address] = m.keySet.toSet
    def optOuterKeys[V](m: Option[SortedMap[Address, V]]): Set[Address] = m.fold(Set.empty[Address])(outerKeys)

    // activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, _]] -- flatten outer Option[Address] AND inner Address keys.
    val allowSpendAddrs: Set[Address] =
      gsi.activeAllowSpends.fold(Set.empty[Address]) { outer =>
        outer.foldLeft(Set.empty[Address]) {
          case (acc, (optOuter, inner)) =>
            acc ++ optOuter.toSet ++ inner.keySet.toSet
        }
      }

    // tokenLockBalances: SortedMap[Address, SortedMap[Address, Balance]] -- flatten outer AND inner Address keys.
    val tokenLockBalanceAddrs: Set[Address] =
      gsi.tokenLockBalances.fold(Set.empty[Address]) { outer =>
        outer.foldLeft(Set.empty[Address]) {
          case (acc, (outerAddr, inner)) =>
            acc + outerAddr ++ inner.keySet.toSet
        }
      }

    outerKeys(gsi.lastStateChannelSnapshotHashes) ++
      outerKeys(gsi.lastCurrencySnapshots) ++
      outerKeys(gsi.lastCurrencySnapshotsProofs) ++
      allowSpendAddrs ++
      optOuterKeys(gsi.activeTokenLocks) ++
      tokenLockBalanceAddrs ++
      optOuterKeys(gsi.lastAllowSpendRefs) ++
      optOuterKeys(gsi.lastTokenLockRefs) ++
      optOuterKeys(gsi.activeDelegatedStakes) ++
      optOuterKeys(gsi.delegatedStakesWithdrawals) ++
      optOuterKeys(gsi.activeNodeCollaterals) ++
      optOuterKeys(gsi.nodeCollateralWithdrawals) ++
      optOuterKeys(gsi.metagraphSyncData)
  }

  /** Apply the ordinal-gated dust sweep as a post-construction transform.
    *
    * Returns `(gsi, false)` unchanged for any ordinal/environment outside the gate (the normal path: one map lookup returning `None`). At
    * exactly a configured sweep ordinal it partitions `balances` by the dust threshold, the empty-ref gate, the exclusion set, and the
    * collection-address guard, credits the collected sum to the treasury (or burns it), prunes the swept addresses' `lastTxRefs`, and
    * returns `(swept gsi, true)`.
    */
  def applyDustSweep(
    gsi: GlobalSnapshotInfo,
    ordinal: SnapshotOrdinal,
    env: AppEnvironment,
    sweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]
  ): (GlobalSnapshotInfo, Boolean) =
    sweeps.get(env).flatMap(_.get(ordinal)) match {
      case None => (gsi, false) // normal path, ~free
      case Some(DustSweep(threshold, collection)) =>
        val protectedAddrs = addressesWithNonBalanceState(gsi) // single union Set[Address]
        val (swept, kept) = gsi.balances.partition {
          case (a, b) =>
            b.value.value <= threshold.value.value &&
            // EMPTY-REF GATE: sweep only never-sent addresses (absent or empty lastTxRef). Closes a transaction-replay
            // vector: pruning a sender's ref would reset its nonce. The 444k dust population is all empty-ref, so zero coverage loss.
            gsi.lastTxRefs.get(a).forall(_ === TransactionReference.empty) &&
            !protectedAddrs.contains(a) &&
            !collection.contains(a)
        }
        // Safe: |dust| * 12345 is far below Long.MaxValue (444,304 * 12345 ~ 5.5e9), and addition is commutative for determinism.
        val collected = swept.values.foldLeft(0L)(_ + _.value.value)
        val newBalances: SortedMap[Address, Balance] = collection match {
          case Some(addr) =>
            val base = kept.getOrElse(addr, Balance.empty).value.value
            kept.updated(addr, Balance(NonNegLong.unsafeFrom(base + collected))) // sweep to treasury
          case None => kept // burn
        }
        val newTxRefs = gsi.lastTxRefs.filter { case (a, _) => newBalances.contains(a) }
        (gsi.copy(balances = newBalances, lastTxRefs = newTxRefs), true)
    }
}
