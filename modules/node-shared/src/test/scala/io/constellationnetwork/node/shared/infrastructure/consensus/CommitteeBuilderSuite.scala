package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

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
}
