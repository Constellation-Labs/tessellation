package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Locks in the v19 multi-committee derivation contract.
  *
  * Each test encodes one rule from the multi-committee design: bootstrap default, tier carry-forward, deterministic Core-floor promotion,
  * and stable witness ordering.
  */
object CommitteeBuilderSuite extends SimpleIOSuite {

  import TierTransitions.{Core, Tier1, Witness}

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  pureTest("bootstrap default: empty priorTiers puts every candidate in Core") {
    val cands = List(pid("p1"), pid("p2"), pid("p3"))
    val result = CommitteeBuilder.build(
      candidates = cands,
      priorTiers = SortedMap.empty[PeerId, Int],
      coreFloor = 0
    )
    expect.same(cands, result.core).and(expect.same(List.empty[PeerId], result.tier1)).and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("priorTiers partitions candidates: Tier 2 -> core, Tier 1 -> tier1, Tier 0 -> witness") {
    val p1 = pid("p1")
    val p2 = pid("p2")
    val p3 = pid("p3")
    val priorTiers = SortedMap[PeerId, Int](p1 -> Core, p2 -> Tier1, p3 -> Witness)
    val result = CommitteeBuilder.build(
      candidates = List(p1, p2, p3),
      priorTiers = priorTiers,
      coreFloor = 0
    )
    expect.same(List(p1), result.core).and(expect.same(List(p2), result.tier1)).and(expect.same(List(p3), result.witness))
  }

  pureTest("Core-floor promotion: lex-sort Tier 1 peers into Core to satisfy the floor") {
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
    // Core=1, need 3, so promote 2 Tier 1 peers. Lex-smallest first: pB, pC.
    val result = CommitteeBuilder.build(
      candidates = List(pA, pB, pC, pD),
      priorTiers = priorTiers,
      coreFloor = 3
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
      coreFloor = 10
    )
    // Tier 1 exhausted -> floor honored to whatever we have.
    expect
      .same(List(p1, p2), result.core)
      .and(expect.same(List.empty[PeerId], result.tier1))
      .and(expect.same(List.empty[PeerId], result.witness))
  }

  pureTest("Core-floor promotion is deterministic across honest nodes (same input -> same output)") {
    val p1 = pid("zzzz")
    val p2 = pid("aaaa")
    val p3 = pid("mmmm")
    val priorTiers = SortedMap[PeerId, Int](p1 -> Tier1, p2 -> Tier1, p3 -> Tier1)
    // floor=2 -> promote lex-smallest 2: aaaa, mmmm. zzzz stays Tier 1.
    val resultA = CommitteeBuilder.build(List(p1, p2, p3), priorTiers, coreFloor = 2)
    val resultB = CommitteeBuilder.build(List(p3, p1, p2), priorTiers, coreFloor = 2)
    expect
      .same(resultA.core.toSet, resultB.core.toSet)
      .and(expect.same(Set(p2, p3), resultA.core.toSet))
      .and(expect.same(List(p1), resultA.tier1))
  }

  pureTest("Candidates outside priorTiers are bootstrap-Core: counted toward the floor") {
    val pNew = pid("new1")
    val pOldCore = pid("oldC")
    val priorTiers = SortedMap[PeerId, Int](pOldCore -> Core)
    // pNew is bootstrap-Core (priorTiers.get(pNew) == None defaults to Core).
    val result = CommitteeBuilder.build(
      candidates = List(pOldCore, pNew),
      priorTiers = priorTiers,
      coreFloor = 2
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
      coreFloor = 2
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
      coreFloor = 0
    )
    // absent stays Tier 1 in effectiveTiers (carried forward, never elevated nor dropped).
    expect.same(Some(Tier1), result.effectiveTiers.get(absent)).and(expect.same(Some(Core), result.effectiveTiers.get(active)))
  }

  pureTest("Empty candidate set produces empty committees (degenerate edge)") {
    val result = CommitteeBuilder.build(
      candidates = List.empty,
      priorTiers = SortedMap.empty,
      coreFloor = 5
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
      coreFloor = 10
    )
    expect
      .same(List(cCore, cTier1), result.core)
      .and(expect.same(List.empty[PeerId], result.tier1))
      .and(expect.same(List(cWit), result.witness))
  }
}
