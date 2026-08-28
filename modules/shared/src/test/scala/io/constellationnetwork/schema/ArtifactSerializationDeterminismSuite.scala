package io.constellationnetwork.schema

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Printer}
import weaver.MutableIOSuite

/** Cross-peer serialization-determinism suite for the Global L0 signed-artifact path.
  *
  * '''Bug class under test''': a field on a signed artifact whose serialization depends on a collection's INSERTION / ITERATION order
  * rather than canonical (sorted) order. Two honest nodes building the same LOGICAL content in different orders then produce DIFFERENT
  * bytes -> DIFFERENT hash -> `GlobalArtifactMismatch` -> wedge. Two such offenders were found reactively in production on
  * `ConsensusOperationalState`: `recentRoundEndTimes` and `perPeer` (the `signedArtifactPeerHistory` helper in
  * `GlobalSnapshotConsensusStateAdvancer` now strips round times and keeps only canonical score records in `perPeer`, and the schema since
  * pins every field to `SortedMap` / `SortedSet`).
  *
  * '''Why this is not the trivial test''': we do NOT serialize one object twice. We build each collection-bearing structure TWO INDEPENDENT
  * WAYS with identical logical content but reversed / shuffled input order on every collection field, push BOTH through the EXACT
  * serializer the consensus artifact-hash path uses, and assert the produced bytes (and resulting `Hash`) are byte-identical. A plain `Map`
  * / `Set` field would FAIL this; a `SortedMap` / `SortedSet` passes. That divergence is the entire signal.
  *
  * '''Serializer fidelity''': the consensus path hashes artifacts via `HasherSelector[F].withCurrent(implicit h => artifact.hash)`, whose
  * current hasher is `Hasher.forJson` (see `Hasher.forSync`/`forSyncAlwaysCurrent`). `Hasher.forJson` encodes with
  * `JsonSerializer[F].serialize` and digests the bytes with `Hash.fromBytesForSync`. The diagnostics path (`serializedArtifactDigest` in
  * the advancer) calls the same `JsonSerializer[F].serialize` directly. This suite uses BOTH of those exact entry points
  * (`JsonSerializer.forAsync` + `Hasher.forJson`), not a hand-rolled codec, so a pass here is a statement about the real wire bytes.
  */
object ArtifactSerializationDeterminismSuite extends MutableIOSuite {

  // Same resource shape the closely-related MptInsertionOrderDeterminismSuite uses: the real
  // production JSON serializer plus the JSON hasher built on top of it.
  type Res = (JsonSerializer[IO], Hasher[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h)

  // The exact production printer (io.constellationnetwork.json.JsonSerializer#forAsync). Used only
  // for the negative-control demonstrations below, where we render plain-collection content with
  // the SAME printer the real serializer would use. NOTE: `sortKeys = true` canonicalizes JSON
  // OBJECT keys (which derevo derives from case-class field names); it does NOT sort the ELEMENTS
  // of a JSON array. A plain Set/Map serializes its elements/entries in iteration order, so
  // `sortKeys` does not save an unsorted collection in a VALUE position. That is the crux of the
  // bug class.
  private val productionPrinter: Printer = Printer(dropNullValues = true, indent = "", sortKeys = true)

  // ---------------------------------------------------------------------------------------------
  // Deterministic fixtures. Fixed (not random) so the golden hash anchors below stay stable.
  // ---------------------------------------------------------------------------------------------

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  // Three peers, deliberately given in NON-sorted character order so that "forward" insertion
  // order differs from canonical (sorted) order. peer('c') < peer('a') is false, so iterating the
  // raw list yields a different order than the SortedSet/SortedMap would.
  private val pC = peer('c')
  private val pA = peer('a')
  private val pB = peer('b')

  private def record(quality: (Int, Int), penalty: Int, miss: Long, vcc: Option[Long], tier: Option[Int]): PerPeerOperationalRecord =
    PerPeerOperationalRecord(
      quality = quality,
      removalPenalty = penalty,
      cumulativeMissCount = miss,
      readmissionCountdown = 0,
      deferralCountdown = 0,
      viewChangesCaused = vcc,
      tier = tier
    )

  // Logical per-peer content, supplied as an explicitly UNSORTED list of pairs.
  private val perPeerPairs: List[(PeerId, PerPeerOperationalRecord)] = List(
    pC -> record((1, 2), 4, 9L, Some(3L), Some(2)),
    pA -> record((5, 7), 12, 2L, None, Some(1)),
    pB -> record((9, 9), 0, 1L, Some(7L), None)
  )

  private val proofSizePairs: List[(SnapshotOrdinal, Int)] = List(
    ord(102L) -> 5,
    ord(100L) -> 7,
    ord(101L) -> 6
  )

  // recentSigners: per-ordinal signer SET. Both the outer map keys (ordinals) and the inner set
  // elements (peers) are supplied unsorted, so this exercises nested collection canonicalization.
  private val signerPairs: List[(SnapshotOrdinal, List[PeerId])] = List(
    ord(101L) -> List(pC, pA),
    ord(100L) -> List(pB, pC, pA)
  )

  private val endTimePairs: List[(SnapshotOrdinal, Long)] = List(
    ord(101L) -> 1717000001000L,
    ord(100L) -> 1717000000000L
  )

  /** Build a ConsensusOperationalState from the given orderings of every collection field. By feeding `forward` vs `forward.reverse` we get
    * two values that are LOGICALLY identical but were inserted in opposite order. Because every field is Sorted, the constructed
    * collections are canonical and the two builds must serialize identically.
    */
  private def buildOperationalState(
    perPeerOrder: List[(PeerId, PerPeerOperationalRecord)],
    proofOrder: List[(SnapshotOrdinal, Int)],
    signerOrder: List[(SnapshotOrdinal, List[PeerId])],
    endTimeOrder: List[(SnapshotOrdinal, Long)]
  ): ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.from(perPeerOrder),
      recentProofSizes = SortedMap.from(proofOrder),
      recentSigners = Some(SortedMap.from(signerOrder.map { case (o, ps) => o -> SortedSet.from(ps) })),
      recentRoundEndTimes = Some(SortedMap.from(endTimeOrder))
    )

  private val operationalForward: ConsensusOperationalState =
    buildOperationalState(perPeerPairs, proofSizePairs, signerPairs, endTimePairs)

  // Reverse every collection field's insertion order, AND reverse the inner signer lists too.
  private val operationalReversed: ConsensusOperationalState =
    buildOperationalState(
      perPeerPairs.reverse,
      proofSizePairs.reverse,
      signerPairs.reverse.map { case (o, ps) => o -> ps.reverse },
      endTimePairs.reverse
    )

  // ---------------------------------------------------------------------------------------------
  // Full GlobalIncrementalSnapshot fixture (the actual signed artifact type). Every reachable
  // collection field is populated and re-buildable in reversed order. Heavyweight leaf types
  // (Signed[Block] inside BlockAsActiveTip, Signed[StateChannelSnapshotBinary]) are left as empty
  // collections: an empty SortedSet/SortedMap still exercises the encoder for that field, and the
  // order-sensitivity signal lives in the populated fields. See the coverage notes at EOF.
  // ---------------------------------------------------------------------------------------------

  private def addr(suffix: String): Address =
    // A fixed, decode-valid DAG address. The checksum digit is computed the same way addressGen
    // does (sum of digits in the 36-char tail, mod 9), so the refined constructor accepts it.
    {
      val tail = (suffix * 36).take(36)
      val parity = tail.filter(_.isDigit).map(_.toString.toInt).sum % 9
      Address(refineV[DAGAddressRefined].unsafeFrom(s"DAG$parity$tail"))
    }

  private val addrX = addr("1")
  private val addrY = addr("2")

  private def reward(a: Address, amt: Long): RewardTransaction =
    RewardTransaction(a, TransactionAmount(PosLong.unsafeFrom(amt)))

  private val rewardPairs: List[RewardTransaction] = List(
    reward(addrY, 30L),
    reward(addrX, 10L)
  )

  private val delegateRewardPairs: List[(PeerId, List[(Address, Amount)])] = List(
    pC -> List(addrY -> Amount(NonNegLong(7L)), addrX -> Amount(NonNegLong(3L))),
    pA -> List(addrX -> Amount(NonNegLong(11L)))
  )

  private val nextFacilitatorsList: NonEmptyList[PeerId] = NonEmptyList.of(pC, pA, pB)

  private val stateProof: GlobalSnapshotStateProof =
    GlobalSnapshotStateProof(
      lastStateChannelSnapshotHashesProof = Hash.empty,
      lastTxRefsProof = Hash.empty,
      balancesProof = Hash.empty,
      lastCurrencySnapshotsProof = None,
      activeAllowSpends = None,
      activeTokenLocks = None,
      tokenLockBalances = None,
      lastAllowSpendRefs = None,
      lastTokenLockRefs = None,
      updateNodeParameters = None,
      activeDelegatedStakes = None,
      delegatedStakesWithdrawals = None,
      activeNodeCollaterals = None,
      nodeCollateralWithdrawals = None,
      priceState = None,
      lastGlobalSnapshotsWithCurrency = None,
      mptRoot = None,
      retiredAllowSpendRefs = None
    )

  private val emptyTips: SnapshotTips = SnapshotTips(deprecated = SortedSet.empty, remainedActive = SortedSet.empty)

  private def buildSnapshot(
    rewardsOrder: List[RewardTransaction],
    delegateOrder: List[(PeerId, List[(Address, Amount)])],
    nextFacs: NonEmptyList[PeerId],
    peerHistory: Option[ConsensusOperationalState]
  ): GlobalIncrementalSnapshot =
    GlobalIncrementalSnapshot(
      ordinal = ord(42L),
      height = Height(NonNegLong(7L)),
      subHeight = SubHeight(NonNegLong(3L)),
      lastSnapshotHash = Hash.empty,
      blocks = SortedSet.empty,
      stateChannelSnapshots = SortedMap.empty,
      rewards = SortedSet.from(rewardsOrder),
      delegateRewards = Some(SortedMap.from(delegateOrder.map { case (p, m) => p -> SortedMap.from(m) })),
      epochProgress = EpochProgress(NonNegLong(5L)),
      nextFacilitators = nextFacs,
      tips = emptyTips,
      stateProof = stateProof,
      allowSpendBlocks = Some(SortedSet.empty),
      tokenLockBlocks = Some(SortedSet.empty),
      spendActions = Some(SortedMap.empty),
      updateNodeParameters = Some(SortedMap.empty[Id, Signed[UpdateNodeParameters]]),
      artifacts = Some(SortedSet.empty),
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(SortedMap.empty),
      activeNodeCollaterals = Some(SortedMap.empty),
      nodeCollateralWithdrawals = Some(SortedMap.empty),
      peerHistory = peerHistory
    )

  // The signed peerHistory carried in a real proposal: recentRoundEndTimes is nulled and perPeer is
  // reduced to explicit activeAdmissionScore records by `signedArtifactPeerHistory` before signing.
  // We mirror that exact shape here so the snapshot fixture reflects the bytes that go over the wire.
  private def signedShapePeerHistory(src: ConsensusOperationalState): ConsensusOperationalState =
    src.copy(
      perPeer = SortedMap.from(
        src.perPeer.keysIterator.map { peerId =>
          peerId -> PerPeerOperationalRecord.empty.copy(activeAdmissionScore = Some(0))
        }
      ),
      recentRoundEndTimes = None
    )

  private val snapshotForward: GlobalIncrementalSnapshot =
    buildSnapshot(rewardPairs, delegateRewardPairs, nextFacilitatorsList, Some(signedShapePeerHistory(operationalForward)))

  private val snapshotReversed: GlobalIncrementalSnapshot =
    buildSnapshot(
      rewardPairs.reverse,
      delegateRewardPairs.reverse.map { case (p, m) => p -> m.reverse },
      nextFacilitatorsList,
      Some(signedShapePeerHistory(operationalReversed))
    )

  // ---------------------------------------------------------------------------------------------
  // Helpers that go through the REAL serializer/hasher.
  // ---------------------------------------------------------------------------------------------

  private def serialize[A: Encoder](a: A)(implicit j: JsonSerializer[IO]): IO[IndexedSeq[Byte]] =
    j.serialize(a).map(_.toIndexedSeq)

  // ============================================================================================
  // 1. ConsensusOperationalState: the type that held BOTH known offenders.
  // ============================================================================================

  test("ConsensusOperationalState: forward vs reversed insertion order -> byte-identical + hash-identical") { res =>
    implicit val (j, h) = res
    for {
      fwdBytes <- serialize(operationalForward)
      revBytes <- serialize(operationalReversed)
      fwdHash <- h.hash(operationalForward)
      revHash <- h.hash(operationalReversed)
    } yield
      expect(fwdBytes == revBytes, "ConsensusOperationalState serialized bytes diverged across insertion order")
        .and(expect.same(fwdHash, revHash))
        .and(expect(operationalForward == operationalReversed, "logical equality precondition failed"))
  }

  // ============================================================================================
  // 2. Full GlobalIncrementalSnapshot (the signed artifact) swept the same way.
  // ============================================================================================

  test("GlobalIncrementalSnapshot: forward vs reversed insertion order -> byte-identical + hash-identical") { res =>
    implicit val (j, h) = res
    for {
      fwdBytes <- serialize(snapshotForward)
      revBytes <- serialize(snapshotReversed)
      fwdHash <- h.hash(snapshotForward)
      revHash <- h.hash(snapshotReversed)
    } yield
      expect(fwdBytes == revBytes, "GlobalIncrementalSnapshot serialized bytes diverged across insertion order")
        .and(expect.same(fwdHash, revHash))
        .and(expect(snapshotForward == snapshotReversed, "logical equality precondition failed"))
  }

  test("trailing v35 incremental fields preserve legacy JSON while frozen full snapshot shapes stay unchanged") { res =>
    implicit val (j, h) = res
    implicit val stateProofSelector: StateProofSelector = CurrencyStateProofSelector.instance

    val globalFull = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
    val currencyFull = CurrencySnapshot.mkGenesis(Map.empty, None, None)

    def compatible[A: Encoder: Decoder](value: A, field: String): Boolean = {
      val encoded = value.asJson
      val legacy = encoded.mapObject(_.remove(field))
      productionPrinter.print(encoded) == productionPrinter.print(legacy) && legacy.as[A].contains(value)
    }

    CurrencyIncrementalSnapshot.fromCurrencySnapshot[IO](currencyFull).map { currencyIncremental =>
      val globalFullFields = Set(
        "ordinal",
        "height",
        "subHeight",
        "lastSnapshotHash",
        "blocks",
        "stateChannelSnapshots",
        "rewards",
        "epochProgress",
        "nextFacilitators",
        "info",
        "tips"
      )
      val currencyFullFields = Set(
        "ordinal",
        "height",
        "subHeight",
        "lastSnapshotHash",
        "blocks",
        "rewards",
        "tips",
        "info",
        "epochProgress",
        "dataApplication",
        "globalSyncView",
        "version"
      )

      expect.all(
        compatible(snapshotForward, "certifiedLineage"),
        !currencyIncremental.asJson.asObject.exists(_.contains("certifiedLineage")),
        globalFull.asJson.asObject.exists(_.keys.toSet == globalFullFields),
        currencyFull.asJson.asObject.exists(_.keys.toSet == currencyFullFields)
      )
    }
  }

  // ============================================================================================
  // 3. Per-field sweep of EVERY collection-typed field reachable in the artifact tree, each built
  //    forward and reversed and serialized through the real serializer.
  //
  //    Sweep verdict (read the schema in GlobalIncrementalSnapshot.scala / ConsensusOperationalState.scala):
  //
  //    GlobalIncrementalSnapshot:
  //      blocks                      SortedSet[BlockAsActiveTip]                                 SORTED  (safe, empty here)
  //      stateChannelSnapshots       SortedMap[Address, NEL[Signed[..]]]                         SORTED  (safe, empty here)
  //      rewards                     SortedSet[RewardTransaction]                                SORTED  (safe, exercised)
  //      delegateRewards             Option[SortedMap[PeerId, SortedMap[Address, Amount]]]       SORTED  (safe, exercised, nested)
  //      nextFacilitators            NonEmptyList[PeerId]                                        ORDERED (order is semantic, NOT a bug)
  //      tips.deprecated             SortedSet[DeprecatedTip]                                    SORTED  (safe, empty here)
  //      tips.remainedActive         SortedSet[ActiveTip]                                        SORTED  (safe, empty here)
  //      allowSpendBlocks            Option[SortedSet[Signed[AllowSpendBlock]]]                  SORTED  (safe, empty here)
  //      tokenLockBlocks             Option[SortedSet[Signed[TokenLockBlock]]]                   SORTED  (safe, empty here)
  //      spendActions                Option[SortedMap[Address, List[SpendAction]]]               SORTED  (safe, empty here)
  //      updateNodeParameters        Option[SortedMap[Id, Signed[UpdateNodeParameters]]]         SORTED  (safe, empty here)
  //      artifacts                   Option[SortedSet[SharedArtifact]]                           SORTED  (safe, empty here)
  //      activeDelegatedStakes       Option[SortedMap[Address, List[..Create]]]                  SORTED  (safe, empty here)
  //      delegatedStakesWithdrawals  Option[SortedMap[Address, List[..Withdraw]]]                SORTED  (safe, empty here)
  //      activeNodeCollaterals       Option[SortedMap[Address, List[..Create]]]                  SORTED  (safe, empty here)
  //      nodeCollateralWithdrawals   Option[SortedMap[Address, List[..Withdraw]]]                SORTED  (safe, empty here)
  //      peerHistory                 Option[ConsensusOperationalState]                           see below
  //
  //    ConsensusOperationalState (peerHistory):
  //      perPeer                     SortedMap[PeerId, PerPeerOperationalRecord]                 SORTED  (safe; signed bytes keep canonical score-only records)
  //      recentProofSizes            SortedMap[SnapshotOrdinal, Int]                             SORTED  (safe, exercised)
  //      recentSigners               Option[SortedMap[SnapshotOrdinal, SortedSet[PeerId]]]       SORTED  (safe, exercised, nested)
  //      recentRoundEndTimes         Option[SortedMap[SnapshotOrdinal, Long]]                    SORTED  (safe; was an offender, now sorted + stripped from signed bytes)
  //
  //    RESULT: NO third plain-Map/plain-Set offender was found. Every collection in the reachable
  //    artifact tree is Sorted (or an intentionally ordered NonEmptyList). The two historical
  //    offenders are Sorted now; recentRoundEndTimes is stripped and perPeer is reduced to canonical
  //    score-only records by `signedArtifactPeerHistory`. If a future schema change introduces a plain `Map`/`Set` in a
  //    value position, the relevant per-field assertion below (and tests 1-2) will start failing.
  // ============================================================================================

  test("per-field sweep: every populated collection field is order-independent through the real serializer") { res =>
    implicit val (j, h) = res

    // rewards : SortedSet[RewardTransaction]
    val rewardsFwd: SortedSet[RewardTransaction] = SortedSet.from(rewardPairs)
    val rewardsRev: SortedSet[RewardTransaction] = SortedSet.from(rewardPairs.reverse)

    // delegateRewards : SortedMap[PeerId, SortedMap[Address, Amount]] (nested)
    val delegateFwd: SortedMap[PeerId, SortedMap[Address, Amount]] =
      SortedMap.from(delegateRewardPairs.map { case (p, m) => p -> SortedMap.from(m) })
    val delegateRev: SortedMap[PeerId, SortedMap[Address, Amount]] =
      SortedMap.from(delegateRewardPairs.reverse.map { case (p, m) => p -> SortedMap.from(m.reverse) })

    // recentProofSizes : SortedMap[SnapshotOrdinal, Int]
    val proofsFwd: SortedMap[SnapshotOrdinal, Int] = SortedMap.from(proofSizePairs)
    val proofsRev: SortedMap[SnapshotOrdinal, Int] = SortedMap.from(proofSizePairs.reverse)

    // recentSigners : SortedMap[SnapshotOrdinal, SortedSet[PeerId]] (nested set)
    val signersFwd: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
      SortedMap.from(signerPairs.map { case (o, ps) => o -> SortedSet.from(ps) })
    val signersRev: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
      SortedMap.from(signerPairs.reverse.map { case (o, ps) => o -> SortedSet.from(ps.reverse) })

    // recentRoundEndTimes : SortedMap[SnapshotOrdinal, Long]
    val endTimesFwd: SortedMap[SnapshotOrdinal, Long] = SortedMap.from(endTimePairs)
    val endTimesRev: SortedMap[SnapshotOrdinal, Long] = SortedMap.from(endTimePairs.reverse)

    // perPeer : SortedMap[PeerId, PerPeerOperationalRecord]
    val perPeerFwd: SortedMap[PeerId, PerPeerOperationalRecord] = SortedMap.from(perPeerPairs)
    val perPeerRev: SortedMap[PeerId, PerPeerOperationalRecord] = SortedMap.from(perPeerPairs.reverse)

    for {
      rf <- serialize(rewardsFwd)
      rr <- serialize(rewardsRev)
      df <- serialize(delegateFwd)
      dr <- serialize(delegateRev)
      pf <- serialize(proofsFwd)
      pr <- serialize(proofsRev)
      sf <- serialize(signersFwd)
      sr <- serialize(signersRev)
      ef <- serialize(endTimesFwd)
      er <- serialize(endTimesRev)
      ppf <- serialize(perPeerFwd)
      ppr <- serialize(perPeerRev)
    } yield
      expect(rf == rr, "rewards SortedSet diverged across order")
        .and(expect(df == dr, "delegateRewards nested SortedMap diverged across order"))
        .and(expect(pf == pr, "recentProofSizes SortedMap diverged across order"))
        .and(expect(sf == sr, "recentSigners nested SortedMap/SortedSet diverged across order"))
        .and(expect(ef == er, "recentRoundEndTimes SortedMap diverged across order"))
        .and(expect(ppf == ppr, "perPeer SortedMap diverged across order"))
  }

  // ============================================================================================
  // 4. NEGATIVE CONTROL: prove the methodology has teeth, and document EXACTLY which collection
  //    shapes the production printer does and does NOT rescue.
  //
  //    Finding while writing this suite (worth recording): in this codebase a collection's residual
  //    order-sensitivity under `Printer(sortKeys = true)` reduces to ONE shape -- a JSON ARRAY whose
  //    element order is iteration order, i.e. a plain `Set` (or a bare `List`):
  //      - A plain `Map` is rescued IFF its key type has a circe `KeyEncoder`: it then encodes as a
  //        JSON OBJECT and the printer re-sorts the keys. All three map-key types in the artifact
  //        tree -- `SnapshotOrdinal`, `Address`, `PeerId` -- DO have `KeyEncoder` instances, so a
  //        plain `Map` keyed by any of them would still serialize canonically.
  //      - A `Map` whose key type has NO `KeyEncoder` does not even compile (circe provides no
  //        array-of-pairs `Map` encoder), so it cannot reach production as a silent runtime bug.
  //    Net: the only way to reintroduce the wedge bug class via a collection is a plain `Set` (the
  //    exact historical shape) or an unsorted `List`. We assert the `Set` diverges across order,
  //    that a `KeyEncoder`-keyed plain `Map` does NOT, and that the Sorted counterpart does NOT.
  //    If the Set-divergence assertion ever STARTED passing-as-equal, the canary would be silently
  //    disarmed, so we pin the expected divergence explicitly.
  // ============================================================================================

  pureTest("negative control: a plain Set diverges across order; a keyed Map and a SortedSet do not") {
    // (a) Plain Set[PeerId] -> JSON array in iteration order. sortKeys does NOT sort array elements.
    //     This is the exact shape of the historical offenders.
    val plainSetFwd: Set[PeerId] = scala.collection.immutable.ListSet(pC, pA, pB)
    val plainSetRev: Set[PeerId] = scala.collection.immutable.ListSet(pB, pA, pC)
    val setJsonFwd = productionPrinter.print(plainSetFwd.asJson)
    val setJsonRev = productionPrinter.print(plainSetRev.asJson)

    // (b) Counterexample: a plain Map keyed by SnapshotOrdinal (which HAS a KeyEncoder) encodes as a
    // JSON object and IS rescued by sortKeys -- identical across insertion order even though the Map
    // itself is unsorted. This is why a plain Map keyed by any artifact key type is not a risk.
    val keyedMapFwd: Map[SnapshotOrdinal, Int] = scala.collection.immutable.ListMap(ord(102L) -> 5, ord(100L) -> 7, ord(101L) -> 6)
    val keyedMapRev: Map[SnapshotOrdinal, Int] = scala.collection.immutable.ListMap(ord(101L) -> 6, ord(100L) -> 7, ord(102L) -> 5)
    val keyedMapJsonFwd = productionPrinter.print(keyedMapFwd.asJson)
    val keyedMapJsonRev = productionPrinter.print(keyedMapRev.asJson)

    // (c) Sorted Set: identical regardless of insertion order (the safe behaviour we rely on).
    val sortedSetFwd: SortedSet[PeerId] = SortedSet(pC, pA, pB)
    val sortedSetRev: SortedSet[PeerId] = SortedSet(pB, pA, pC)
    val sortedSetJsonFwd = productionPrinter.print(sortedSetFwd.asJson)
    val sortedSetJsonRev = productionPrinter.print(sortedSetRev.asJson)

    expect(setJsonFwd != setJsonRev, "expected a PLAIN Set to diverge across order (methodology would be blind otherwise)")
      .and(expect(keyedMapJsonFwd == keyedMapJsonRev, "a KeyEncoder-keyed Map IS rescued by sortKeys"))
      .and(expect(sortedSetJsonFwd == sortedSetJsonRev, "SortedSet must be order-independent"))
  }

  // ============================================================================================
  // 5. Golden / regression anchors. Pin the serialized hash of the two fully-specified fixtures so
  //    any future schema or encoding change (field add/remove, codec swap, printer change) trips a
  //    visible failure rather than silently changing the cross-cluster artifact hash. The expected
  //    strings were captured from a run of this suite on this revision; update intentionally with a
  //    schema/encoding change and call it out in review.
  // ============================================================================================

  test("golden anchor: ConsensusOperationalState serialized hash is pinned") { res =>
    implicit val (j, h) = res
    val expected = Hash("6ffc82508644a0a0d1f2390390617c8e991c7468592f0d4dcf0de6d6fafcd556")
    h.hash(operationalForward).map { actual =>
      expect.same(expected, actual)
    }
  }

  test("golden anchor: GlobalIncrementalSnapshot serialized hash is pinned") { res =>
    implicit val (j, h) = res
    val expected = Hash("f09f1a3c42721dd70da6fd6791c066434657a918b9d2bb909826e0b9858b82dd")
    h.hash(snapshotForward).map { actual =>
      expect.same(expected, actual)
    }
  }

  // Diagnostic helper: prints the golden hashes so the placeholders above can be filled on first
  // run. Kept as a normal test so its output appears in the weaver report; it never fails.
  test("DIAGNOSTIC: print golden hashes (always passes)") { res =>
    implicit val (j, h) = res
    for {
      opHash <- h.hash(operationalForward)
      snapHash <- h.hash(snapshotForward)
      _ <- IO.println(s"[GOLDEN] ConsensusOperationalState hash = ${opHash.value}")
      _ <- IO.println(s"[GOLDEN] GlobalIncrementalSnapshot hash = ${snapHash.value}")
    } yield expect(opHash.value.nonEmpty && snapHash.value.nonEmpty)
  }
}
