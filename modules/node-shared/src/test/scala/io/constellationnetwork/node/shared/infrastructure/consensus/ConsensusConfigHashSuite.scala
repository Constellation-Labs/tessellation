package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import weaver.SimpleIOSuite

/** Pins which ConsensusConfig fields feed `deterministicConfigHash`. Consensus-critical knobs must change the hash so divergent operator
  * values are rejected at the L0 join fence; timing-only knobs must not.
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

  pureTest("schema v37 golden: the default deterministicConfigHash is pinned") {
    // Re-approve deliberately whenever a deterministicConfigHash input or consensusSchemaVersion changes.
    expect.same(37, baseConfig.consensusSchemaVersion) &&
    expect.same("a014ebd1067ec533ddfddd9c9042ef6996635c490cb507fed60c855b83c0a90f", baseConfig.deterministicConfigHash.value)
  }

  pureTest("divergent quorum threshold between operators is detected before consensus") {
    val operatorA = baseConfig.copy(quorumThresholdFraction = 2.0 / 3.0)
    val operatorB = baseConfig.copy(quorumThresholdFraction = 0.75)
    expect(operatorA.deterministicConfigHash != operatorB.deterministicConfigHash)
  }

  pureTest("min-committee-size is a deterministic-config fence") {
    expect(baseConfig.copy(minCommitteeSize = 3).deterministicConfigHash != baseConfig.copy(minCommitteeSize = 10).deterministicConfigHash)
  }

  pureTest("clearing is active from its activation ordinal onward and dormant when unconfigured") {
    val gated = baseConfig.copy(gl0ClearingActivationOrdinal = 100L)
    expect(!baseConfig.clearingActiveAt(0L)) &&
    expect(!gated.clearingActiveAt(99L)) &&
    expect(gated.clearingActiveAt(100L)) &&
    expect(gated.clearingActiveAt(101L))
  }

  pureTest("v35 activation key is a deterministic-config fence and is active from the key onward") {
    val first = baseConfig.copy(certifiedConsensusActivationKey = 100L)
    val second = baseConfig.copy(certifiedConsensusActivationKey = 101L)

    expect(first.deterministicConfigHash != second.deterministicConfigHash)
      .and(expect(!first.certifiedConsensusActiveAt(99L)))
      .and(expect(first.certifiedConsensusActiveAt(100L)))
      .and(expect(first.certifiedConsensusActiveAt(101L)))
  }

  pureTest("absent v35 activation remains dormant") {
    expect
      .same(Long.MaxValue, baseConfig.certifiedConsensusActivationKey)
      .and(expect(!baseConfig.certifiedConsensusActiveAt(0L)))
  }

  pureTest("maxRoundDuration joins the deterministic hash when it bounds certified end time") {
    val short = baseConfig.copy(maxRoundDuration = Some(1.minute))
    val long = baseConfig.copy(maxRoundDuration = Some(2.minutes))

    expect(short.deterministicConfigHash != long.deterministicConfigHash)
  }

  pureTest("signatureGracePeriod is in deterministicConfigHash (it bounds the certified commit evidence)") {
    val a = baseConfig.copy(signatureGracePeriod = 3.seconds)
    val b = baseConfig.copy(signatureGracePeriod = 500.milliseconds)
    expect(a.deterministicConfigHash != b.deterministicConfigHash)
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

  pureTest("Currency snapshot protocol v1 activation participates in the deterministic join fence") {
    val dormant = baseConfig.copy(currencySnapshotProtocolV1ActivationOrdinal = Long.MaxValue)
    val scheduled = baseConfig.copy(currencySnapshotProtocolV1ActivationOrdinal = 6000000L)

    expect(dormant.deterministicConfigHash != scheduled.deterministicConfigHash)
  }
}
