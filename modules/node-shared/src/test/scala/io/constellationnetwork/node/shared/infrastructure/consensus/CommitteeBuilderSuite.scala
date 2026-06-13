package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Locks in the v19 multi-committee derivation contract.
  *
  * Each test encodes one rule from the multi-committee design: quality-aware bootstrap default, quality-degradation override,
  * quality-proven bootstrap, tier carry-forward, deterministic Core-floor promotion, and stable witness ordering.
  *
  * `minObs = 3` and `minRatio = 0.5` are the suite-wide defaults; tests that exercise the quality knobs override locally.
  */
object CommitteeBuilderSuite extends SimpleIOSuite {

  import TierTransitions.{Core, Tier1, Witness}

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private val NoQuality: Map[PeerId, (Int, Int)] = Map.empty
  private val MinObs: Int = 3
  private val MinRatio: Double = 0.5

  pureTest("bootstrap default at genesis: empty priorTiers + empty peerQuality + coreFloor=0 puts every candidate in Tier 1") {
    val cands = List(pid("p1"), pid("p2"), pid("p3"))
    val result = CommitteeBuilder.build(
      candidates = cands,
      priorTiers = SortedMap.empty[PeerId, Int],
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // Structural fix: without proven quality, peers join the witness pool (Tier 1),
    // not the liveness quorum. Closes the original "everyone defaults to Core" bootstrap
    // that let chronic-but-unclassified community peers wedge the cluster.
    expect.same(List.empty[PeerId], result.core).and(expect.same(cands, result.tier1)).and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("priorTiers partitions candidates: Tier 2 -> core, Tier 1 -> tier1, Tier 0 -> witness") {
    val p1 = pid("p1")
    val p2 = pid("p2")
    val p3 = pid("p3")
    val priorTiers = SortedMap[PeerId, Int](p1 -> Core, p2 -> Tier1, p3 -> Witness)
    val result = CommitteeBuilder.build(
      candidates = List(p1, p2, p3),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect.same(List(p1), result.core).and(expect.same(List(p2), result.tier1)).and(expect.same(List(p3), result.witness))
  }

  pureTest("non-core probation peer cannot carry prior Core tier into quorum") {
    val pCore = pid("core")
    val pProbation = pid("prob")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pProbation -> Core)
    val result = CommitteeBuilder.build(
      candidates = List(pCore, pProbation),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio,
      nonCorePeers = Set(pProbation)
    )

    expect.same(List(pCore), result.core) &&
    expect.same(List(pProbation), result.tier1) &&
    expect.same(Some(Tier1), result.effectiveTiers.get(pProbation))
  }

  pureTest("non-core probation peer is skipped by Core-floor promotion") {
    val pCore = pid("core")
    val pProbation = pid("prob")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pProbation -> Tier1)
    val result = CommitteeBuilder.build(
      candidates = List(pCore, pProbation),
      priorTiers = priorTiers,
      peerQuality = Map(pProbation -> (10, 10)),
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio,
      nonCorePeers = Set(pProbation)
    )

    expect.same(List(pCore), result.core) &&
    expect.same(List(pProbation), result.tier1) &&
    expect.same(Some(Tier1), result.effectiveTiers.get(pProbation))
  }

  pureTest("Core-floor promotion at genesis falls back to lex order when peerQuality is empty") {
    val pA = pid("0001")
    val pB = pid("0002")
    val pC = pid("0003")
    val pD = pid("0004")
    val priorTiers = SortedMap[PeerId, Int](
      pA -> Core,
      pB -> Tier1,
      pC -> Tier1,
      pD -> Tier1
    )
    // Core=1, need 3, so promote 2 Tier 1 peers. Empty peerQuality -> rank key (1, 0, 0, peerId)
    // is uniform -> lex order on peerId breaks ties. pB, pC are lex-smallest among Tier 1.
    val result = CommitteeBuilder.build(
      candidates = List(pA, pB, pC, pD),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 3,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect.same(List(pA, pB, pC), result.core).and(expect.same(List(pD), result.tier1)).and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("Core-floor honored only to candidate supply (builder never invents peers)") {
    val p1 = pid("p1")
    val p2 = pid("p2")
    val priorTiers = SortedMap[PeerId, Int](p1 -> Core, p2 -> Tier1)
    val result = CommitteeBuilder.build(
      candidates = List(p1, p2),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 10,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // Tier 1 exhausted -> floor honored to whatever we have.
    expect
      .same(List(p1, p2), result.core)
      .and(expect.same(List.empty[PeerId], result.tier1))
      .and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("Core-floor promotion at genesis is deterministic across honest nodes (lex tie-break is stable)") {
    val p1 = pid("zzzz")
    val p2 = pid("aaaa")
    val p3 = pid("mmmm")
    val priorTiers = SortedMap[PeerId, Int](p1 -> Tier1, p2 -> Tier1, p3 -> Tier1)
    // floor=2, empty peerQuality -> promote lex-smallest 2: aaaa, mmmm. zzzz stays Tier 1.
    val resultA =
      CommitteeBuilder.build(List(p1, p2, p3), priorTiers, NoQuality, coreFloor = 2, minObservations = MinObs, minRatio = MinRatio)
    val resultB =
      CommitteeBuilder.build(List(p3, p1, p2), priorTiers, NoQuality, coreFloor = 2, minObservations = MinObs, minRatio = MinRatio)
    expect
      .same(resultA.core.toSet, resultB.core.toSet)
      .and(expect.same(Set(p2, p3), resultA.core.toSet))
      .and(expect.same(List(p1), resultA.tier1))
  }

  pureTest("Candidates outside priorTiers default to Tier 1 but Core-floor can promote them") {
    val pNew = pid("new1")
    val pOldCore = pid("oldC")
    val priorTiers = SortedMap[PeerId, Int](pOldCore -> Core)
    // pNew defaults to Tier 1 (no priorTier, no quality data). coreFloor=2 with only 1
    // existing Core triggers a single promotion -> pNew is promoted to satisfy the floor.
    val result = CommitteeBuilder.build(
      candidates = List(pOldCore, pNew),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect
      .same(List(pOldCore, pNew), result.core)
      .and(expect.same(List.empty[PeerId], result.tier1))
      .and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("effectiveTiers stamps Core for every Core peer (including promoted Tier 1)") {
    val pA = pid("0001")
    val pB = pid("0002")
    val pC = pid("0003")
    val priorTiers = SortedMap[PeerId, Int](pA -> Tier1, pB -> Tier1, pC -> Tier1)
    val result = CommitteeBuilder.build(
      candidates = List(pA, pB, pC),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // pA, pB promoted into Core; pC remains Tier 1.
    expect
      .same(Some(Core), result.effectiveTiers.get(pA))
      .and(expect.same(Some(Core), result.effectiveTiers.get(pB)))
      .and(expect.same(Some(Tier1), result.effectiveTiers.get(pC)))
  }

  pureTest("effectiveTiers carries forward un-candidate peers unchanged") {
    val active = pid("act1")
    val absent = pid("abs1")
    val priorTiers = SortedMap[PeerId, Int](active -> Core, absent -> Tier1)
    val result = CommitteeBuilder.build(
      candidates = List(active),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // absent stays Tier 1 in effectiveTiers (carried forward, never elevated nor dropped).
    expect.same(Some(Tier1), result.effectiveTiers.get(absent)).and(expect.same(Some(Core), result.effectiveTiers.get(active)))
  }

  pureTest("Empty candidate set produces empty committees (degenerate edge)") {
    val result = CommitteeBuilder.build(
      candidates = List.empty,
      priorTiers = SortedMap.empty,
      peerQuality = NoQuality,
      coreFloor = 5,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect
      .same(List.empty[PeerId], result.core)
      .and(expect.same(List.empty[PeerId], result.tier1))
      .and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("Mixed candidates: Witness peers never promoted by Core-floor mechanism") {
    val cCore = pid("CORE")
    val cTier1 = pid("TIE1")
    val cWit = pid("0WIT")
    val priorTiers = SortedMap[PeerId, Int](cCore -> Core, cTier1 -> Tier1, cWit -> Witness)
    // Even with high floor, Witness peers are not promoted -- only Tier 1 is.
    val result = CommitteeBuilder.build(
      candidates = List(cCore, cTier1, cWit),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 10,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect
      .same(List(cCore, cTier1), result.core)
      .and(expect.same(List.empty[PeerId], result.tier1))
      .and(expect.same(List(cWit), result.witness))
  }

  // -- Quality-aware tier defaults --

  pureTest("quality-degradation override: Core peer with ratio below minRatio is forced to Tier 1") {
    val pBad = pid("bad1")
    val pGood = pid("good1")
    val priorTiers = SortedMap[PeerId, Int](pBad -> Core, pGood -> Core)
    // pBad: 1/5 = 0.2 < 0.5, participated >= 3 -> degraded -> Tier 1 regardless of priorTier.
    // pGood: 4/5 = 0.8 >= 0.5 -> stays Core.
    val quality = Map(pBad -> (1, 5), pGood -> (4, 5))
    val result = CommitteeBuilder.build(
      candidates = List(pBad, pGood),
      priorTiers = priorTiers,
      peerQuality = quality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect.same(List(pGood), result.core).and(expect.same(List(pBad), result.tier1)).and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("quality-degradation does NOT fire below minObservations (insufficient evidence -> trust priorTier)") {
    val pNew = pid("ng01")
    val priorTiers = SortedMap[PeerId, Int](pNew -> Core)
    // 0/2 ratio is below 0.5 but participated=2 < minObs=3 -> degradation does not fire,
    // priorTier Core wins.
    val quality = Map(pNew -> (0, 2))
    val result = CommitteeBuilder.build(
      candidates = List(pNew),
      priorTiers = priorTiers,
      peerQuality = quality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect.same(List(pNew), result.core).and(expect.same(List.empty[PeerId], result.tier1))
  }

  pureTest("quality-proven bootstrap: new peer (absent from priorTiers) with proven quality lands in Core") {
    val pNew = pid("nb01")
    // Empty priorTiers. pNew has 4/5 = 0.8 ratio with participated >= 3 -> quality-proven -> Core.
    val quality = Map(pNew -> (4, 5))
    val result = CommitteeBuilder.build(
      candidates = List(pNew),
      priorTiers = SortedMap.empty[PeerId, Int],
      peerQuality = quality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    expect.same(List(pNew), result.core).and(expect.same(List.empty[PeerId], result.tier1))
  }

  pureTest("quality-aware Core-floor promotion: highest-ratio Tier 1 peer is promoted first") {
    val pLowRatio = pid("low1")
    val pHighRatio = pid("hi01")
    val pNoData = pid("nd01")
    val priorTiers = SortedMap[PeerId, Int](pLowRatio -> Tier1, pHighRatio -> Tier1, pNoData -> Tier1)
    // pHighRatio: 4/5 = 0.8 -> would be quality-proven Core anyway, but we want to test promotion ranking.
    // To keep all three Tier 1 going into promotion, use just-above-degradation ratios.
    // pLowRatio: 3/5 = 0.6
    // pHighRatio: 5/5 = 1.0
    // pNoData: no quality -> rank key (1, ...) sorts last.
    val quality = Map(pLowRatio -> (3, 5), pHighRatio -> (5, 5))
    // Both pLowRatio and pHighRatio are quality-proven so they'd be Core on their own.
    // To force the promotion path, give priorTiers Tier 1 -- the carried-forward classification wins.
    val result = CommitteeBuilder.build(
      candidates = List(pLowRatio, pHighRatio, pNoData),
      priorTiers = priorTiers,
      peerQuality = quality,
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // rawCore=0, deficit=2. Ranked: pHighRatio (0,-1000000,-5,...), pLowRatio (0,-600000,-3,...), pNoData (1,...).
    // Promoted first 2: pHighRatio, pLowRatio.
    expect.same(Set(pHighRatio, pLowRatio), result.core.toSet).and(expect.same(List(pNoData), result.tier1))
  }

  pureTest("degraded Core peer is overridden BEFORE Core-floor counting (forces a deficit)") {
    val pDegradedCore = pid("dg01")
    val pCleanTier1 = pid("ct01")
    val priorTiers = SortedMap[PeerId, Int](pDegradedCore -> Core, pCleanTier1 -> Tier1)
    // pDegradedCore is Core in priorTiers but ratio 1/5 = 0.2 < 0.5 -> demoted to Tier 1.
    // pCleanTier1 has no quality data -> Tier 1 carried forward.
    // rawCore = 0, deficit = 1. Promotion ranks Tier 1 [pDegradedCore (low ratio), pCleanTier1 (no data)].
    // pDegradedCore: (0, -200000, -1, ...). pCleanTier1: (1, 0, 0, ...). pDegradedCore ranks ahead.
    // So a degraded peer can still bubble back into Core via the floor mechanism -- this is
    // intentional. The override pulls them out of the structural Core seat for THIS round so
    // the round's quorum denominator reflects the carried Core set minus the degraded one;
    // the floor then refills from whichever Tier 1 peers rank highest. The signal is "if you
    // need them in Core to make quorum at all, take them, but you'd rather not."
    val quality = Map(pDegradedCore -> (1, 5))
    val result = CommitteeBuilder.build(
      candidates = List(pDegradedCore, pCleanTier1),
      priorTiers = priorTiers,
      peerQuality = quality,
      coreFloor = 1,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // rawCore=[], rawTier1=[pDegradedCore, pCleanTier1]. Promote 1.
    // pDegradedCore's rank (0,-200000,-1,hex) sorts before pCleanTier1's (1,0,0,hex).
    expect.same(List(pDegradedCore), result.core).and(expect.same(List(pCleanTier1), result.tier1))
  }

  // -- Chronic-core replacement ladder --

  pureTest("chronic Core member is swapped for the highest-score non-chronic reserve") {
    val pChronic = pid("chr1")
    val pCore = pid("cor1")
    val pLowScore = pid("low1")
    val pHighScore = pid("hi01")
    val priorTiers = SortedMap[PeerId, Int](pChronic -> Core, pCore -> Core, pLowScore -> Tier1, pHighScore -> Tier1)
    val result = CommitteeBuilder.build(
      candidates = List(pChronic, pCore, pLowScore, pHighScore),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio,
      chronicMisses = Map(pChronic -> 5),
      activeScores = Map(pChronic -> 150, pCore -> 150, pLowScore -> 60, pHighScore -> 120)
    )

    // pChronic is excluded from Core regardless of its score; pHighScore (120 > 60)
    // is the deterministic one-for-one replacement; pChronic lands in Tier 1 (still
    // signs and earns, no longer in the quorum denominator).
    expect.same(List(pCore, pHighScore), result.core) &&
    expect.same(List(pChronic, pLowScore), result.tier1) &&
    expect.same(List(pChronic -> 5), result.chronicExcluded) &&
    expect.same(List(pHighScore), result.chronicReplacements) &&
    expect.same(List.empty[(PeerId, Int)], result.chronicReadmitted) &&
    expect.same(Some(Tier1), result.effectiveTiers.get(pChronic)) &&
    expect.same(Some(Core), result.effectiveTiers.get(pHighScore))
  }

  pureTest("floor does NOT re-promote a chronic peer when a healthy reserve exists") {
    val pCore = pid("cor1")
    val pChronicGoodQuality = pid("chrq")
    val pHealthyNoData = pid("heal")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pChronicGoodQuality -> Tier1, pHealthyNoData -> Tier1)
    // pChronicGoodQuality has a perfect cumulative ratio and would win the quality-ranked
    // floor promotion without the chronic gate. The evidence says it has stopped signing,
    // so the floor must skip it and promote the bootstrap-blank healthy peer instead.
    val result = CommitteeBuilder.build(
      candidates = List(pCore, pChronicGoodQuality, pHealthyNoData),
      priorTiers = priorTiers,
      peerQuality = Map(pChronicGoodQuality -> (5, 5)),
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio,
      chronicMisses = Map(pChronicGoodQuality -> 4)
    )

    expect.same(List(pCore, pHealthyNoData), result.core) &&
    expect.same(List(pChronicGoodQuality), result.tier1) &&
    expect.same(List.empty[(PeerId, Int)], result.chronicExcluded) &&
    expect.same(List.empty[PeerId], result.chronicReplacements)
  }

  pureTest("supply-short ladder: Core shrinks below the floor rather than padding with chronic peers") {
    val pA = pid("0001")
    val pB = pid("0002")
    val pChr1 = pid("0003")
    val pChr2 = pid("0004")
    val priorTiers = SortedMap[PeerId, Int](pA -> Core, pB -> Core, pChr1 -> Core, pChr2 -> Core)
    val result = CommitteeBuilder.build(
      candidates = List(pA, pB, pChr1, pChr2),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 4,
      minObservations = MinObs,
      minRatio = MinRatio,
      chronicMisses = Map(pChr1 -> 4, pChr2 -> 6)
    )

    // No non-chronic reserves -> no replacement, no floor padding. Core shrinks to the
    // 2 healthy members (= MinViableCoreSize, so no re-admission either). A 2-member
    // all-healthy Core is strictly more live than a 4-member one with 2 dead seats.
    expect.same(List(pA, pB), result.core) &&
    expect.same(List(pChr1, pChr2), result.tier1) &&
    expect.same(List(pChr1 -> 4, pChr2 -> 6), result.chronicExcluded) &&
    expect.same(List.empty[(PeerId, Int)], result.chronicReadmitted)
  }

  pureTest("supply-short ladder: least-bad chronic peers are re-admitted below MinViableCoreSize") {
    val pHealthy = pid("heal")
    val pChrWorse = pid("0bad")
    val pChrBetter = pid("0okk")
    val priorTiers = SortedMap[PeerId, Int](pHealthy -> Core, pChrWorse -> Core, pChrBetter -> Core)
    val result = CommitteeBuilder.build(
      candidates = List(pHealthy, pChrWorse, pChrBetter),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 4,
      minObservations = MinObs,
      minRatio = MinRatio,
      chronicMisses = Map(pChrWorse -> 7, pChrBetter -> 4)
    )

    // Excluding both chronic members would leave Core at 1 < MinViableCoreSize (2), so
    // the liveness fallback re-admits the least-bad chronic peer (lowest miss count).
    expect.same(List(pHealthy, pChrBetter), result.core) &&
    expect.same(List(pChrWorse), result.tier1) &&
    expect.same(List(pChrWorse -> 7), result.chronicExcluded) &&
    expect.same(List(pChrBetter -> 4), result.chronicReadmitted) &&
    expect.same(Some(Core), result.effectiveTiers.get(pChrBetter))
  }

  pureTest("ladder re-admission never bypasses the probation (nonCorePeers) gate") {
    val pHealthy = pid("heal")
    val pChronicProb = pid("prob")
    val priorTiers = SortedMap[PeerId, Int](pHealthy -> Core, pChronicProb -> Core)
    val result = CommitteeBuilder.build(
      candidates = List(pHealthy, pChronicProb),
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 2,
      minObservations = MinObs,
      minRatio = MinRatio,
      nonCorePeers = Set(pChronicProb),
      chronicMisses = Map(pChronicProb -> 9)
    )

    expect.same(List(pHealthy), result.core) &&
    expect.same(List(pChronicProb), result.tier1) &&
    expect.same(List.empty[(PeerId, Int)], result.chronicReadmitted)
  }

  pureTest("chronic ladder is deterministic under map-ordering permutations") {
    val pA = pid("0001")
    val pB = pid("0002")
    val pC = pid("0003")
    val pD = pid("0004")
    val pE = pid("0005")
    val priorTiers = SortedMap[PeerId, Int](pA -> Core, pB -> Core, pC -> Tier1, pD -> Tier1, pE -> Tier1)
    val candidates = List(pA, pB, pC, pD, pE)
    // Same contents, permuted insertion order: small immutable Maps (Map1..Map4 and the
    // builder beyond) iterate in insertion order, so a derivation that leaked map iteration
    // order into the result would diverge between these two calls.
    val quality1 = Map(pC -> (3, 5), pD -> (4, 5), pE -> (5, 5), pA -> (5, 5))
    val quality2 = Map(pA -> (5, 5), pE -> (5, 5), pD -> (4, 5), pC -> (3, 5))
    val scores1 = Map(pC -> 60, pD -> 90, pE -> 90, pA -> 150)
    val scores2 = Map(pA -> 150, pE -> 90, pD -> 90, pC -> 60)
    val chronic1 = Map(pB -> 5, pE -> 4)
    val chronic2 = Map(pE -> 4, pB -> 5)

    def buildWith(quality: Map[PeerId, (Int, Int)], scores: Map[PeerId, Int], chronic: Map[PeerId, Int]): CommitteeBuilder.Committees =
      CommitteeBuilder.build(
        candidates = candidates,
        priorTiers = priorTiers,
        peerQuality = quality,
        coreFloor = 3,
        minObservations = MinObs,
        minRatio = MinRatio,
        chronicMisses = chronic,
        activeScores = scores
      )

    val result1 = buildWith(quality1, scores1, chronic1)
    val result2 = buildWith(quality2, scores2, chronic2)

    // pB (chronic Core) is swapped for pD (score 90, lex-smaller than pE which is chronic
    // anyway); the floor then promotes pC. Identical full result either way.
    expect.same(result1, result2) &&
    expect.same(List(pA, pD, pC), result1.core) &&
    expect.same(List(pB, pE), result1.tier1)
  }

  // -- Bounded one-slot Tier-1 reward rotation --

  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  pureTest("rotation is inert (output byte-identical) when rewardRotationEpochRounds = 0") {
    val pCore = pid("core")
    val pTier1 = pid("tie1")
    val pWit = pid("0wit")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pTier1 -> Tier1, pWit -> Witness)
    val candidates = List(pCore, pTier1, pWit)
    val baseline = CommitteeBuilder.build(
      candidates = candidates,
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio
    )
    // Same call but with all rotation inputs supplied and the lane DISABLED (epoch 0). Even with
    // a demonstrated-live witness and a non-empty tier1, the result must equal the baseline.
    val withDisabledRotation = CommitteeBuilder.build(
      candidates = candidates,
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio,
      rotationKey = Some(ord(10)),
      recentParticipants = Set(pWit),
      idleWindows = _ => 9,
      tenureWindows = _ => 9,
      rewardRotationEpochRounds = 0
    )
    expect.same(baseline, withDisabledRotation)
  }

  pureTest("rotation is inert off an epoch boundary") {
    val pCore = pid("core")
    val pTier1 = pid("tie1")
    val pWit = pid("0wit")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pTier1 -> Tier1, pWit -> Witness)
    val candidates = List(pCore, pTier1, pWit)
    val baseline = CommitteeBuilder.build(candidates, priorTiers, NoQuality, coreFloor = 0, minObservations = MinObs, minRatio = MinRatio)
    val offBoundary = CommitteeBuilder.build(
      candidates = candidates,
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio,
      rotationKey = Some(ord(13)), // not a multiple of 10
      recentParticipants = Set(pWit),
      idleWindows = _ => 9,
      tenureWindows = _ => 9,
      rewardRotationEpochRounds = 10
    )
    expect.same(baseline, offBoundary)
  }

  pureTest("on an epoch boundary, a demonstrated-live Witness peer is rotated into Tier 1 and a Tier-1 peer rotated out") {
    val pCore = pid("core")
    val pTier1 = pid("tie1")
    val pWit = pid("0wit")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pTier1 -> Tier1, pWit -> Witness)
    val candidates = List(pCore, pTier1, pWit)
    val result = CommitteeBuilder.build(
      candidates = candidates,
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio,
      rotationKey = Some(ord(20)), // boundary at epoch 10
      recentParticipants = Set(pWit),
      idleWindows = _ => 5,
      tenureWindows = _ => 3,
      rewardRotationEpochRounds = 10
    )
    // pWit (demonstrated-live) takes the rotating Tier-1 seat; pTier1 moves to the front of witness.
    // Core is untouched. Sizes of tier1 and witness are invariant (one in, one out).
    expect.same(List(pCore), result.core) &&
    expect.same(List(pWit), result.tier1) &&
    expect.same(List(pTier1), result.witness) &&
    expect.same(Some(Tier1), result.effectiveTiers.get(pWit)) &&
    expect.same(Some(Witness), result.effectiveTiers.get(pTier1)) &&
    expect.same(Some(Core), result.effectiveTiers.get(pCore))
  }

  pureTest("rotation NEVER seats a peer absent from recentParticipants (liveness gate)") {
    val pCore = pid("core")
    val pTier1 = pid("tie1")
    val pWit = pid("0wit")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pTier1 -> Tier1, pWit -> Witness)
    val candidates = List(pCore, pTier1, pWit)
    val baseline = CommitteeBuilder.build(candidates, priorTiers, NoQuality, coreFloor = 0, minObservations = MinObs, minRatio = MinRatio)
    // pWit is NOT in recentParticipants -> eligibleWaiting is empty -> no rotation even on a boundary.
    val result = CommitteeBuilder.build(
      candidates = candidates,
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio,
      rotationKey = Some(ord(20)),
      recentParticipants = Set.empty,
      idleWindows = _ => 5,
      tenureWindows = _ => 3,
      rewardRotationEpochRounds = 10
    )
    expect.same(baseline, result)
  }

  pureTest("rotation never alters Core even when a Core peer is the only demonstrated-live candidate") {
    val pCore = pid("core")
    val pTier1 = pid("tie1")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, pTier1 -> Tier1)
    val candidates = List(pCore, pTier1)
    // recentParticipants = {pCore}, but pCore is in core, so eligibleWaiting (candidates - core -
    // tier1) is empty. Core safety: the rotation cannot pull a Core peer out or seat into Core.
    val baseline = CommitteeBuilder.build(candidates, priorTiers, NoQuality, coreFloor = 0, minObservations = MinObs, minRatio = MinRatio)
    val result = CommitteeBuilder.build(
      candidates = candidates,
      priorTiers = priorTiers,
      peerQuality = NoQuality,
      coreFloor = 0,
      minObservations = MinObs,
      minRatio = MinRatio,
      rotationKey = Some(ord(30)),
      recentParticipants = Set(pCore),
      idleWindows = _ => 9,
      tenureWindows = _ => 9,
      rewardRotationEpochRounds = 10
    )
    expect.same(baseline, result) &&
    expect.same(List(pCore), result.core)
  }

  pureTest("rotation is deterministic across input-order permutations") {
    val pCore = pid("core")
    val t1 = pid("0ta1")
    val t2 = pid("0tb2")
    val w1 = pid("0wc1")
    val w2 = pid("0wd2")
    val priorTiers = SortedMap[PeerId, Int](pCore -> Core, t1 -> Tier1, t2 -> Tier1, w1 -> Witness, w2 -> Witness)
    def run(candidates: List[PeerId]): CommitteeBuilder.Committees =
      CommitteeBuilder.build(
        candidates = candidates,
        priorTiers = priorTiers,
        peerQuality = NoQuality,
        coreFloor = 0,
        minObservations = MinObs,
        minRatio = MinRatio,
        rotationKey = Some(ord(40)),
        recentParticipants = Set(w1, w2),
        idleWindows = Map(w1 -> 4, w2 -> 4).getOrElse(_, 0), // equal -> lottery tiebreak
        tenureWindows = Map(t1 -> 2, t2 -> 2).getOrElse(_, 0), // equal -> PeerId tiebreak
        rewardRotationEpochRounds = 10
      )
    val a = run(List(pCore, t1, t2, w1, w2))
    val b = run(List(w2, t2, pCore, w1, t1))
    expect.same(a.core, b.core) &&
    expect.same(a.tier1.toSet, b.tier1.toSet) &&
    expect.same(a.witness.toSet, b.witness.toSet) &&
    expect.same(a.effectiveTiers, b.effectiveTiers) &&
    expect.same(2, a.tier1.size)
  }
}
