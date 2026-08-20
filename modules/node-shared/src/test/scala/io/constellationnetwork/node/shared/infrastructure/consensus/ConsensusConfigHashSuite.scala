package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import weaver.SimpleIOSuite

/** Locks in the v20 `coreCommitteeSize` participation in `deterministicConfigHash`.
  *
  * ==Why this suite exists==
  *
  * Pre-v20 `SnapshotConfig.coreCommitteeSize: Map[AppEnvironment, PosInt]` was env-keyed and resolved at the consensus construction site
  * without participating in the hash. Two operators with divergent Core size values would compute different Core committees (the LIVENESS
  * quorum denominator) but compute IDENTICAL `deterministicConfigHash` values, so their Facility messages would carry matching `configHash`
  * and they would proceed to silently fork.
  *
  * v20 routes the env-resolved value through `ConsensusConfig.coreCommitteeSize: Option[Int]` (populated by `GlobalSnapshotConsensus` /
  * `CurrencySnapshotConsensus` at the construction site) and folds it into the hash. Honest operators with divergent Core size values now
  * compute different hashes. L0 advertises this exact effective hash at joining, where mismatched or one-sided values are rejected;
  * Facility processing provides an additional diagnostic. The advertised `versionHash` independently fences software releases.
  *
  * This suite asserts the invariant directly on `ConsensusConfig.deterministicConfigHash`:
  *
  *   - Same Core size -> same hash (positive case);
  *   - Different Core size -> different hash (regression-guard);
  *   - Absent vs `Some(3)` -> same hash (back-compat: the `None` default is treated as `3`, matching the pre-v20 `getOrElse(3)`
  *     resolution).
  *
  * The "different env -> different hash because Core size differs by env" case is established by the same mechanism: the construction site
  * resolves the value differently per env (via `SnapshotConfig.coreCommitteeSize.get(env)`), so the resulting `ConsensusConfig` has
  * different `coreCommitteeSize` values and therefore different hashes. We assert that programmatically.
  */
object ConsensusConfigHashSuite extends SimpleIOSuite {

  // Minimal but valid ConsensusConfig fixture. Field values match the dev defaults except where the
  // suite specifically varies them. The hash is computed lazily, so only the values that feed the
  // hash string matter for these tests.
  private val eventCutter: EventCutterConfig =
    EventCutterConfig(
      maxBinarySizeBytes = PosInt(1024),
      maxUpdateNodeParametersSize = PosInt(1024)
    )

  private def baseConfig: ConsensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = eventCutter
    )

  pureTest("same coreCommitteeSize -> identical deterministicConfigHash") {
    val a = baseConfig.copy(coreCommitteeSize = Some(5))
    val b = baseConfig.copy(coreCommitteeSize = Some(5))
    expect.same(a.deterministicConfigHash, b.deterministicConfigHash)
  }

  pureTest("different coreCommitteeSize -> different deterministicConfigHash") {
    val testnetLike = baseConfig.copy(coreCommitteeSize = Some(5))
    val mainnetLike = baseConfig.copy(coreCommitteeSize = Some(15))
    expect(testnetLike.deterministicConfigHash != mainnetLike.deterministicConfigHash)
  }

  pureTest("None coreCommitteeSize matches Some(3) (back-compat with pre-v20 getOrElse(3))") {
    val absent = baseConfig.copy(coreCommitteeSize = None)
    val explicit = baseConfig.copy(coreCommitteeSize = Some(3))
    expect.same(absent.deterministicConfigHash, explicit.deterministicConfigHash)
  }

  pureTest("dev (3) vs testnet (5) -> different join hash after env resolution") {
    val devConfig = baseConfig.copy(coreCommitteeSize = Some(3))
    val testnetConfig = baseConfig.copy(coreCommitteeSize = Some(5))
    expect(devConfig.deterministicConfigHash != testnetConfig.deterministicConfigHash)
  }

  pureTest("testnet (5) vs mainnet (15) vs integrationnet (9) -> three distinct hashes") {
    val t = baseConfig.copy(coreCommitteeSize = Some(5)).deterministicConfigHash
    val m = baseConfig.copy(coreCommitteeSize = Some(15)).deterministicConfigHash
    val i = baseConfig.copy(coreCommitteeSize = Some(9)).deterministicConfigHash
    expect(t != m).and(expect(t != i)).and(expect(m != i))
  }

  pureTest("regression marker: divergent Core size between operators has a distinct join fingerprint") {
    // Operator A configures Core=5, operator B configures Core=6 by accident. Pre-v20 both would
    // produce the same hash and their Facility messages would interoperate; the fork would emerge
    // downstream when their Core committee derivations diverged. The L0 join gate now rejects the
    // mismatch before Facility processing.
    val operatorA = baseConfig.copy(coreCommitteeSize = Some(5))
    val operatorB = baseConfig.copy(coreCommitteeSize = Some(6))
    expect(operatorA.deterministicConfigHash != operatorB.deterministicConfigHash)
  }

  pureTest("tier1SignatureGracePeriod is NOT in deterministicConfigHash (timing-only, same as signatureGracePeriod)") {
    // Both grace periods are finalization-timing levers: the canonical snapshotHash is the agreed
    // ARTIFACT hash, not the signed-artifact hash, so divergent grace values produce the same
    // downstream hash and must NOT enter the config hash (otherwise honest operators with different
    // timing would produce a noisy diagnostic mismatch). This guards the documented treatment.
    val a = baseConfig.copy(tier1SignatureGracePeriod = 750.milliseconds, signatureGracePeriod = 3.seconds)
    val b = baseConfig.copy(tier1SignatureGracePeriod = 2.seconds, signatureGracePeriod = 9.seconds)
    expect.same(a.deterministicConfigHash, b.deterministicConfigHash)
  }

  pureTest("EventTrigger batching and cooldown are NOT in deterministicConfigHash (local scheduling only)") {
    val oneAtATime = baseConfig.copy(eventTriggerThreshold = 1, eventTriggerCooldown = 5.seconds)
    val batched = baseConfig.copy(eventTriggerThreshold = 9, eventTriggerCooldown = 43.seconds)

    expect.same(oneAtATime.deterministicConfigHash, batched.deterministicConfigHash)
  }

  pureTest("the environment-resolved facilitator selector cap participates independently in the join hash") {
    val a = baseConfig.copy(maxFacilitatorCount = Some(PosInt(20)), facilitatorSelectionMax = Some(1000))
    val b = baseConfig.copy(maxFacilitatorCount = Some(PosInt(20)), facilitatorSelectionMax = Some(300))

    expect(a.deterministicConfigHash != b.deterministicConfigHash) &&
    expect.same(Some(PosInt(20)), a.maxFacilitatorCount)
  }

  pureTest("Currency/Global retained-window semantics participate in the deterministic join fence") {
    val base = baseConfig.copy(lastGlobalSnapshotSyncOffset = 2L, lastGlobalSnapshotsInMemory = 50)
    val differentOffset = base.copy(lastGlobalSnapshotSyncOffset = 3L)
    val differentRetention = base.copy(lastGlobalSnapshotsInMemory = 51)

    expect(base.deterministicConfigHash != differentOffset.deterministicConfigHash) &&
    expect(base.deterministicConfigHash != differentRetention.deterministicConfigHash) &&
    expect(differentOffset.deterministicConfigHash != differentRetention.deterministicConfigHash)
  }
}
