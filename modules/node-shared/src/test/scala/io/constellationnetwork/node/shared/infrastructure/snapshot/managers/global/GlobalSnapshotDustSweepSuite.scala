package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.merkletree.{Proof, ProofEntry}
import io.constellationnetwork.node.shared.config.types.DustSweep
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generators.addressGen
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.snapshot.MetagraphSyncDataInfo
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendReference}
import io.constellationnetwork.schema.tokenLock.TokenLockReference
import io.constellationnetwork.schema.transaction.{TransactionOrdinal, TransactionReference}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

/** Tests for the deterministic, ordinal-gated GSI dust sweep. See `GlobalSnapshotDustSweep` and the proposal at
  * `.workspace/gsi-dust-sweep-proposal-20260609.md`.
  */
object GlobalSnapshotDustSweepSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp, j)

  // MptRoot format selector (post-migration) so allStateEntries/buildMpt is exercised.
  implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal.unsafeApply(Long.MaxValue))

  private val testEnv: AppEnvironment = AppEnvironment.Dev
  private val sweepOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(1000L)
  private val otherOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(999L)

  // Distinct test addresses (DAG-prefixed refined literals).
  private val dust1 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val dust2 = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWC")
  private val dust3 = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")
  private val whale = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU")
  private val treasury = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebT")

  private val dustValue = 12345L
  private val threshold = Balance(NonNegLong.unsafeFrom(100000L)) // 0.001 DAG

  private def bal(v: Long): Balance = Balance(NonNegLong.unsafeFrom(v))

  private def burnSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]] =
    Map(testEnv -> SortedMap(sweepOrdinal -> DustSweep(threshold, none)))

  private def treasurySweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]] =
    Map(testEnv -> SortedMap(sweepOrdinal -> DustSweep(threshold, treasury.some)))

  // A non-empty lastTxRef (a "has sent" sender): ordinal 1, non-empty hash.
  private val nonEmptyTxRef: TransactionReference =
    TransactionReference(TransactionOrdinal(1L), Hash("a" * 64))

  private def gsiWith(
    balances: SortedMap[Address, Balance],
    lastTxRefs: SortedMap[Address, TransactionReference] = SortedMap.empty,
    activeDelegatedStakes: Option[SortedMap[Address, SortedSet[DelegatedStakeRecord]]] = None
  ): GlobalSnapshotInfo =
    GlobalSnapshotInfo.empty.copy(
      balances = balances,
      lastTxRefs = lastTxRefs,
      activeDelegatedStakes = activeDelegatedStakes.orElse(Some(SortedMap.empty))
    )

  private def mkStakeRecord(source: Address): DelegatedStakeRecord = {
    val nodeId = Id(Hex("1234567890abcdef"))
    DelegatedStakeRecord(
      Signed(
        UpdateDelegatedStake.Create(
          source = source,
          nodeId = nodeId.toPeerId,
          amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(1000L)),
          fee = DelegatedStakeFee(NonNegLong.unsafeFrom(0L)),
          tokenLockRef = Hash.empty
        ),
        NonEmptySet.one[SignatureProof](SignatureProof(nodeId, Signature(Hex(Hash.empty.value))))
      ),
      SnapshotOrdinal.unsafeApply(1L),
      Balance.empty,
      none,
      none
    )
  }

  // --- Test 1: determinism -------------------------------------------------------------------

  test("determinism: two independent sweeps of identical pre-state yield identical state roots") { res =>
    implicit val (h, _, j) = res

    val balances = SortedMap(
      dust1 -> bal(dustValue),
      dust2 -> bal(dustValue),
      dust3 -> bal(dustValue),
      whale -> bal(50_000_000L)
    )
    val pre = gsiWith(balances)

    val (sweptA, didA) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, treasurySweeps)
    val (sweptB, didB) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, treasurySweeps)

    for {
      rootA <- sweptA.allStateEntries[IO].buildMpt
      rootB <- sweptB.allStateEntries[IO].buildMpt
    } yield
      expect.all(
        didA,
        didB,
        sweptA === sweptB,
        rootA === rootB
      )
  }

  // --- Test 2: exclusion coverage (reflective/structural) ------------------------------------

  test("exclusion coverage: addressesWithNonBalanceState references every Address-keyed GSI field") { _ =>
    // Mechanical guard 1: partition every GlobalSnapshotInfo field name into Address-keyed vs non-Address-keyed.
    // If a future field is added, productElementNames grows and the exhaustiveness assertion below fails,
    // forcing whoever adds it to categorize it (and, if Address-keyed, extend addressesWithNonBalanceState).
    val allFieldNames: Set[String] = GlobalSnapshotInfo.empty.productElementNames.toSet

    val addressKeyedFields: Set[String] = Set(
      "lastStateChannelSnapshotHashes",
      "lastTxRefs",
      "lastCurrencySnapshots",
      "lastCurrencySnapshotsProofs",
      "activeAllowSpends",
      "activeTokenLocks",
      "tokenLockBalances",
      "lastAllowSpendRefs",
      "lastTokenLockRefs",
      "activeDelegatedStakes",
      "delegatedStakesWithdrawals",
      "activeNodeCollaterals",
      "nodeCollateralWithdrawals",
      "metagraphSyncData",
      "retiredAllowSpendRefs"
    )

    // `balances` is the swept field; updateNodeParameters is Id-keyed; priceState is TokenPair-keyed.
    val nonAddressKeyedFields: Set[String] = Set("balances", "updateNodeParameters", "priceState")

    // Mechanical guard 2: build a GSI with a UNIQUE sentinel address keyed into every Address-keyed field
    // (except lastCurrencySnapshots, whose value requires a Signed[CurrencySnapshot]) and assert the function
    // returns every sentinel. If any covered field were dropped from the function, its sentinel would be missing.
    val slots = List(
      "lastStateChannelSnapshotHashes",
      "lastTxRefs",
      "lastCurrencySnapshotsProofs",
      "activeAllowSpendsOuter",
      "activeAllowSpendsInner",
      "activeTokenLocks",
      "tokenLockBalancesOuter",
      "tokenLockBalancesInner",
      "lastAllowSpendRefs",
      "lastTokenLockRefs",
      "activeDelegatedStakes",
      "delegatedStakesWithdrawals",
      "activeNodeCollaterals",
      "nodeCollateralWithdrawals",
      "metagraphSyncData",
      "retiredAllowSpendRefsOuter",
      "retiredAllowSpendRefsInner"
    )

    // Distinct, valid (parity-correct) sentinel addresses, one per slot.
    val distinctAddrs: List[Address] =
      Iterator.continually(addressGen.sample).flatten.distinct.take(slots.size).toList
    val sentinels: Map[String, Address] = slots.zip(distinctAddrs).toMap

    val proof = Proof(NonEmptyList.one(ProofEntry(Hash.empty, Hash.empty.asLeft[Hash])))

    val populated: GlobalSnapshotInfo = GlobalSnapshotInfo.empty.copy(
      lastStateChannelSnapshotHashes = SortedMap(sentinels("lastStateChannelSnapshotHashes") -> Hash.empty),
      lastTxRefs = SortedMap(sentinels("lastTxRefs") -> TransactionReference.empty),
      lastCurrencySnapshotsProofs = SortedMap(sentinels("lastCurrencySnapshotsProofs") -> proof),
      activeAllowSpends = Some(
        SortedMap(
          sentinels("activeAllowSpendsOuter").some ->
            SortedMap(sentinels("activeAllowSpendsInner") -> SortedSet.empty[Signed[AllowSpend]])
        )
      ),
      activeTokenLocks = Some(SortedMap(sentinels("activeTokenLocks") -> SortedSet.empty)),
      tokenLockBalances = Some(
        SortedMap(
          sentinels("tokenLockBalancesOuter") -> SortedMap(sentinels("tokenLockBalancesInner") -> Balance.empty)
        )
      ),
      lastAllowSpendRefs = Some(SortedMap(sentinels("lastAllowSpendRefs") -> AllowSpendReference.empty)),
      lastTokenLockRefs = Some(SortedMap(sentinels("lastTokenLockRefs") -> TokenLockReference.empty)),
      activeDelegatedStakes = Some(SortedMap(sentinels("activeDelegatedStakes") -> SortedSet.empty)),
      delegatedStakesWithdrawals = Some(SortedMap(sentinels("delegatedStakesWithdrawals") -> SortedSet.empty)),
      activeNodeCollaterals = Some(SortedMap(sentinels("activeNodeCollaterals") -> SortedSet.empty)),
      nodeCollateralWithdrawals = Some(SortedMap(sentinels("nodeCollateralWithdrawals") -> SortedSet.empty)),
      metagraphSyncData = Some(SortedMap(sentinels("metagraphSyncData") -> MetagraphSyncDataInfo.empty)),
      retiredAllowSpendRefs = Some(
        SortedMap(
          sentinels("retiredAllowSpendRefsOuter").some ->
            SortedMap(sentinels("retiredAllowSpendRefsInner") -> SortedMap.empty[Hash, EpochProgress])
        )
      )
    )

    val covered = GlobalSnapshotDustSweep.addressesWithNonBalanceState(populated)

    // The behaviorally-populated slots cover every Address-keyed field except lastCurrencySnapshots
    // (whose value would require a Signed[CurrencySnapshot]). Tie the slot set to the field set so that a
    // newly-added Address-keyed field that is not behaviorally exercised here trips this assertion too.
    val behaviorallyTestedFields: Set[String] = Set(
      "lastStateChannelSnapshotHashes",
      "lastCurrencySnapshotsProofs",
      "activeAllowSpends",
      "activeTokenLocks",
      "tokenLockBalances",
      "lastAllowSpendRefs",
      "lastTokenLockRefs",
      "activeDelegatedStakes",
      "delegatedStakesWithdrawals",
      "activeNodeCollaterals",
      "nodeCollateralWithdrawals",
      "metagraphSyncData",
      "retiredAllowSpendRefs"
    )

    // whale is keyed ONLY into balances => the swept field => must NOT be reported as protected.
    val balanceOnly = gsiWith(SortedMap(whale -> bal(dustValue)))

    // lastTxRefs is Address-keyed but handled by the EMPTY-REF GATE, not the exclusion: every dust receiver holds an empty-ref
    // lastTxRefs entry, so excluding lastTxRefs keys would exclude the entire dust population and the sweep would remove nothing.
    val gateHandledFields: Set[String] = Set("lastTxRefs")

    IO {
      expect.all(
        // every GSI field is categorized exactly once (fails if a field is added and left uncategorized)
        addressKeyedFields.union(nonAddressKeyedFields) === allFieldNames,
        addressKeyedFields.intersect(nonAddressKeyedFields).isEmpty,
        // the behaviorally-tested set is exactly the EXCLUSION fields (Address-keyed minus the gate-handled lastTxRefs) minus the
        // one value-heavy field that cannot be cheaply constructed (lastCurrencySnapshots)
        behaviorallyTestedFields === (addressKeyedFields -- gateHandledFields - "lastCurrencySnapshots"),
        // every behaviorally-populated EXCLUSION sentinel (including both nested inner-map keys) is captured
        (sentinels -- gateHandledFields).values.toSet.subsetOf(covered),
        // regression guard: the gate-handled lastTxRefs sentinel must NOT be reported as protected (else the sweep would exclude
        // the entire empty-ref dust population and remove nothing)
        !covered.contains(sentinels("lastTxRefs")),
        // `balances` is NOT an excluded field (it is the swept field): a balance-only address is sweepable
        !GlobalSnapshotDustSweep.addressesWithNonBalanceState(balanceOnly).contains(whale)
      )
    }
  }

  // --- Test 3: safety (dust + delegated stake => NOT swept, stake intact) --------------------

  test("safety: a dust-balance address with a delegated stake is not swept and its stake is intact") { _ =>
    val stakeRecord = mkStakeRecord(dust1)
    val pre = gsiWith(
      balances = SortedMap(dust1 -> bal(dustValue), dust2 -> bal(dustValue), whale -> bal(50_000_000L)),
      activeDelegatedStakes = Some(SortedMap(dust1 -> SortedSet(stakeRecord)))
    )

    val (swept, did) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, burnSweeps)

    IO {
      expect.all(
        did,
        // dust1 is staked => excluded => kept
        swept.balances.contains(dust1),
        swept.balances.get(dust1) === bal(dustValue).some,
        // dust2 is pure dust => swept
        !swept.balances.contains(dust2),
        // whale above threshold => kept
        swept.balances.contains(whale),
        // stake map is untouched
        swept.activeDelegatedStakes == pre.activeDelegatedStakes
      )
    }
  }

  // --- Test 4: sweep arithmetic (burn drops supply; treasury preserves) ----------------------

  test("sweep arithmetic: burn drops supply by the swept sum; treasury sweep preserves supply") { _ =>
    val balances = SortedMap(
      dust1 -> bal(dustValue),
      dust2 -> bal(dustValue),
      dust3 -> bal(dustValue),
      whale -> bal(50_000_000L)
    )
    val pre = gsiWith(balances)
    val supplyBefore = balances.values.foldLeft(0L)(_ + _.value.value)
    val sweptSum = 3L * dustValue

    val (burned, didBurn) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, burnSweeps)
    val (swept, didSweep) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, treasurySweeps)

    val burnedSupply = burned.balances.values.foldLeft(0L)(_ + _.value.value)
    val treasurySupply = swept.balances.values.foldLeft(0L)(_ + _.value.value)

    IO {
      expect.all(
        didBurn,
        didSweep,
        // burn: dust gone, supply dropped by exactly the swept sum
        burnedSupply === supplyBefore - sweptSum,
        burned.balances.keySet.toSet === Set(whale),
        // treasury: supply preserved, collected sum credited to treasury, 444k-style entries collapse to 1
        treasurySupply === supplyBefore,
        swept.balances.get(treasury) === bal(sweptSum).some,
        swept.balances.keySet.toSet === Set(whale, treasury)
      )
    }
  }

  // --- Test 5: off-ordinal no-op (returns (gsi,false), byte-identical) ------------------------

  test("off-ordinal no-op: applyDustSweep at a non-sweep ordinal returns (gsi, false) unchanged") { _ =>
    val balances = SortedMap(dust1 -> bal(dustValue), dust2 -> bal(dustValue), whale -> bal(50_000_000L))
    val pre = gsiWith(balances)

    val (resOther, didOther) = GlobalSnapshotDustSweep.applyDustSweep(pre, otherOrdinal, testEnv, burnSweeps)
    val (resEnv, didEnv) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, AppEnvironment.Mainnet, burnSweeps)
    val (resEmpty, didEmpty) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, Map.empty)

    IO {
      expect.all(
        !didOther,
        !didEnv,
        !didEmpty,
        // reference-identical input on the no-op path
        resOther eq pre,
        resEnv eq pre,
        resEmpty eq pre
      )
    }
  }

  // --- Test 6: empty-ref gate (a dust address that has sent is NOT swept) ---------------------

  test("empty-ref gate: a sender (non-empty ref) is kept; empty-ref receivers (absent OR Some(empty)) are swept") { _ =>
    // dust3 carries an explicit empty-ref lastTxRefs entry -- the REAL dust shape: a pure receiver gets a
    // Some(TransactionReference.empty) entry (live state: ~444k of ~444.5k lastTxRefs entries are empty). It MUST be swept.
    // Regression guard: if lastTxRefs were in the exclusion set, dust3 and the whole empty-ref population would be excluded and the
    // sweep would remove nothing.
    val pre = gsiWith(
      balances = SortedMap(dust1 -> bal(dustValue), dust2 -> bal(dustValue), dust3 -> bal(dustValue), whale -> bal(50_000_000L)),
      lastTxRefs = SortedMap(dust1 -> nonEmptyTxRef, dust3 -> TransactionReference.empty)
    )

    val (swept, did) = GlobalSnapshotDustSweep.applyDustSweep(pre, sweepOrdinal, testEnv, burnSweeps)

    IO {
      expect.all(
        did,
        // dust1 has a non-empty ref => excluded by the empty-ref gate => kept, and its ref survives
        swept.balances.contains(dust1),
        swept.lastTxRefs.get(dust1) === nonEmptyTxRef.some,
        // dust2 is an absent-ref receiver => swept
        !swept.balances.contains(dust2),
        // dust3 is a Some(empty)-ref receiver (the real dust shape) => swept, and its empty ref is pruned
        !swept.balances.contains(dust3),
        !swept.lastTxRefs.contains(dust3),
        // whale is above threshold => kept
        swept.balances.contains(whale)
      )
    }
  }
}
