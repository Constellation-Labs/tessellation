package io.constellationnetwork.dag.l0.domain.snapshot.programs

import io.constellationnetwork.dag.l0.domain.snapshot.programs.Download.PeerTip
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite

/** Unit tests for `Download.chooseObservationLimit`.
  *
  * The predicate decides whether an `Observing` validator caught up to a stable cluster tip may exit the observe loop early (by targeting
  * `localOrdinal` instead of `localOrdinal + 1`) or must keep waiting for a new snapshot. Wrong answers have real-world consequences: a
  * false "caught up" promotes a validator to Ready on a fork; a false "not caught up" keeps the cluster deadlocked (this was the Apr 20
  * 2026 testnet incident).
  */
object DownloadSuite extends FunSuite {

  private val observationOffset = NonNegLong(1L)
  private val localOrd: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(3106669L)
  private val localHash: Hash = Hash("d106f9cf7acdb5d6fc1f0b0dd4538357ce7944c57452b278dc1212dfbcc8285b")
  private val altHashSameOrd: Hash = Hash("abcdef0000000000000000000000000000000000000000000000000000000000")
  private val nextOrd: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(3106670L)
  private val prevOrd: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(3106668L)
  private val prevHash: Hash = Hash("db444d682f7f32a1209accceafe069bccc920c975117103077aa7f8e06dabcdd")
  private val nextHash: Hash = Hash("1234567000000000000000000000000000000000000000000000000000000000")

  private def decide(tips: List[PeerTip]): SnapshotOrdinal =
    Download.chooseObservationLimit(localOrd, localHash, tips, observationOffset)

  // ── Safe-default (do not shortcut) cases ──────────────────────────────────────

  test("no Ready peers respond: fall through to next-ordinal observe") {
    expect.same(nextOrd, decide(List.empty))
  }

  test("single Ready peer at same ord but DIFFERENT hash: running fork — reject shortcut") {
    // minReadyQuorum=1 allows single-peer decisions, but the hash-identity check still
    // protects against a peer on a different chain at our ordinal.
    expect.same(nextOrd, decide(List(PeerTip(localOrd, altHashSameOrd))))
  }

  test("two Ready peers at same ord but DIFFERENT hashes: running fork — reject shortcut") {
    // Two responses but they disagree on hash at the "same" ordinal; not majority-safe.
    val tips = List(PeerTip(localOrd, localHash), PeerTip(localOrd, altHashSameOrd))
    expect.same(nextOrd, decide(tips))
  }

  test("single Ready peer at same (ord, hash) matching local: shortcut (rollback-lead topology)") {
    // Rollback-lead + validators topology: until the first validator transitions to Ready,
    // the validators' only Ready peer is the single rollback-lead. This must work.
    val tips = List(PeerTip(localOrd, localHash))
    expect.same(localOrd, decide(tips))
  }

  test("majority Ready peers strictly ahead: not caught up — do not shortcut") {
    val tips = List(
      PeerTip(nextOrd, nextHash),
      PeerTip(nextOrd, nextHash),
      PeerTip(localOrd, localHash)
    )
    expect.same(nextOrd, decide(tips))
  }

  test("three Ready peers, no strict majority on any (ordinal, hash): reject") {
    val tips = List(
      PeerTip(localOrd, localHash),
      PeerTip(localOrd, altHashSameOrd),
      PeerTip(nextOrd, nextHash)
    )
    expect.same(nextOrd, decide(tips))
  }

  test("LOCAL is on a fork: majority of Ready peers at our ordinal but different hash — reject") {
    // We are on a fork; peers' majority is on the canonical chain. Must NOT shortcut.
    val tips = List(PeerTip(localOrd, altHashSameOrd), PeerTip(localOrd, altHashSameOrd))
    expect.same(nextOrd, decide(tips))
  }

  // ── Shortcut cases ────────────────────────────────────────────────────────────

  test("two Ready peers at same (ordinal, hash) matching local: shortcut") {
    val tips = List(PeerTip(localOrd, localHash), PeerTip(localOrd, localHash))
    expect.same(localOrd, decide(tips))
  }

  test("three Ready peers: two at local ord/hash + one ahead, majority caught up: shortcut") {
    // Majority (2 of 3) agrees at our tip; one peer is ahead on a newer chain.
    // If majority of Ready peers is at our tip, we are caught up to the majority-chain view.
    val tips = List(
      PeerTip(localOrd, localHash),
      PeerTip(localOrd, localHash),
      PeerTip(nextOrd, nextHash)
    )
    expect.same(localOrd, decide(tips))
  }

  test("two Ready peers agreeing one ordinal BEHIND local: we lead, shortcut") {
    // Local tip is slightly ahead of peers' advertised tip (common during rollout).
    // Nothing new to observe; caught up.
    val tips = List(PeerTip(prevOrd, prevHash), PeerTip(prevOrd, prevHash))
    expect.same(localOrd, decide(tips))
  }

  test("three Ready peers all at same stale ordinal: shortcut") {
    val tips = List(
      PeerTip(prevOrd, prevHash),
      PeerTip(prevOrd, prevHash),
      PeerTip(prevOrd, prevHash)
    )
    expect.same(localOrd, decide(tips))
  }
}
