package io.constellationnetwork.dag.l0.infrastructure.snapshot

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object GlobalSnapshotEventsPublisherDaemonSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  private val peerA = peer('a')
  private val peerB = peer('b')
  private val peerC = peer('c')
  private val formerPeer = peer('d')
  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(value))
  private val integrationnetMinimum = GlobalSnapshotEventsPublisherDaemon.minimumEventTriggerParticipants(
    bootstrapCompleteProofsThreshold = 3
  )

  pureTest("silent retained seats do not count as EventTrigger participants") {
    val participants = GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(
      facilitators = Set(peerA, peerB, peerC),
      proofSigners = Set(peerA)
    )

    expect.same(1, participants) &&
    expect(!GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(participants, integrationnetMinimum))
  }

  pureTest("historical checkpoint proofs outside the recovery-seeded committee do not bypass the solo guard") {
    val participants = GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(
      facilitators = Set(peerA),
      proofSigners = Set(peerB, peerC, formerPeer)
    )

    expect.same(0, participants) &&
    expect(!GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(participants, integrationnetMinimum))
  }

  pureTest("raw proof count does not count a signer outside the current committee") {
    val participants = GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(
      facilitators = Set(peerA, peerB),
      proofSigners = Set(peerA, formerPeer)
    )

    expect.same(1, participants) &&
    expect(!GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(participants, integrationnetMinimum))
  }

  pureTest("two current artifact signers remain TimeTrigger-paced at IntegrationNet's bootstrap threshold") {
    val participants = GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(
      facilitators = Set(peerA, peerB, peerC),
      proofSigners = Set(peerA, peerB, formerPeer)
    )

    expect.same(2, participants) &&
    expect(!GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(participants, integrationnetMinimum))
  }

  pureTest("three current artifact signers enable EventTrigger at IntegrationNet's bootstrap threshold") {
    val participants = GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(
      facilitators = Set(peerA, peerB, peerC),
      proofSigners = Set(peerA, peerB, peerC, formerPeer)
    )

    expect.same(3, participants) &&
    expect(GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(participants, integrationnetMinimum))
  }

  pureTest("a two-validator deployment can reuse its configured bootstrap threshold") {
    val twoValidatorMinimum = GlobalSnapshotEventsPublisherDaemon.minimumEventTriggerParticipants(
      bootstrapCompleteProofsThreshold = 2
    )

    expect.same(2, twoValidatorMinimum) &&
    expect(GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(2, twoValidatorMinimum))
  }

  pureTest("the EventTrigger participant threshold can never permit a solo producer") {
    val misconfiguredMinimum = GlobalSnapshotEventsPublisherDaemon.minimumEventTriggerParticipants(
      bootstrapCompleteProofsThreshold = 1
    )

    expect.same(2, misconfiguredMinimum) &&
    expect(!GlobalSnapshotEventsPublisherDaemon.hasSufficientEventTriggerParticipation(1, misconfiguredMinimum))
  }

  pureTest("follower headroom uses a supermajority of every responsive peer, including unknown keys") {
    val expected = ordinal(101L)
    val headroom = GlobalSnapshotEventsPublisherDaemon.followerHeadroom(
      expected,
      responsivePeerIds = Set(peerB, peerC, formerPeer),
      peerCurrentKeys = Map(peerB -> expected, peerC -> expected),
      selfId = peerA
    )

    expect.same(3, headroom.aligned) &&
    expect.same(4, headroom.total) &&
    expect.same(3, headroom.required) &&
    expect(headroom.allowsAcceleration)
  }

  pureTest("a behind follower closes state-channel acceleration without blocking normal consensus") {
    val expected = ordinal(101L)
    val headroom = GlobalSnapshotEventsPublisherDaemon.followerHeadroom(
      expected,
      responsivePeerIds = Set(peerB, peerC, formerPeer),
      peerCurrentKeys = Map(peerB -> expected, peerC -> ordinal(100L)),
      selfId = peerA
    )

    expect.same(2, headroom.aligned) &&
    expect.same(3, headroom.required) &&
    expect(!headroom.allowsAcceleration)
  }

  pureTest("state-channel trigger intent resets on a completed-outcome generation change") {
    val hashA = Hash("a" * 64)
    val hashB = Hash("b" * 64)
    val firstGeneration = Some(GlobalSnapshotEventsPublisherDaemon.EventTriggerGeneration(ordinal(100L), hashA))
    val nextGeneration = Some(GlobalSnapshotEventsPublisherDaemon.EventTriggerGeneration(ordinal(101L), hashB))
    val first = GlobalSnapshotEventsPublisherDaemon.StateChannelTriggerIntent.empty.record(firstGeneration, hashA)
    val next = first.record(nextGeneration, hashB)

    expect.same(Set(hashA), first.hashes) &&
    expect.same(Set(hashB), next.hashes)
  }

  pureTest("state-channel trigger intent resets when recovery replaces the same ordinal with a different hash") {
    val eventHashA = Hash("a" * 64)
    val eventHashB = Hash("b" * 64)
    val generationA = Some(GlobalSnapshotEventsPublisherDaemon.EventTriggerGeneration(ordinal(100L), Hash("c" * 64)))
    val generationB = Some(GlobalSnapshotEventsPublisherDaemon.EventTriggerGeneration(ordinal(100L), Hash("d" * 64)))
    val first = GlobalSnapshotEventsPublisherDaemon.StateChannelTriggerIntent.empty.record(generationA, eventHashA)
    val recovered = first.record(generationB, eventHashB)

    expect.same(Set(eventHashB), recovered.hashes)
  }

  pureTest("semantic re-delivery cannot inflate a state-channel trigger batch") {
    val hash = Hash("a" * 64)
    val generation = Some(GlobalSnapshotEventsPublisherDaemon.EventTriggerGeneration(ordinal(100L), Hash("c" * 64)))
    val once = GlobalSnapshotEventsPublisherDaemon.StateChannelTriggerIntent.empty.record(generation, hash)
    val twice = once.record(generation, hash)

    expect.same(Set(hash), twice.hashes)
  }

  pureTest("consuming captured intent does not remove a later state-channel event") {
    val hashA = Hash("a" * 64)
    val hashB = Hash("b" * 64)
    val generation = Some(GlobalSnapshotEventsPublisherDaemon.EventTriggerGeneration(ordinal(100L), Hash("c" * 64)))
    val captured = GlobalSnapshotEventsPublisherDaemon.StateChannelTriggerIntent.empty.record(generation, hashA)
    val withLaterArrival = captured.record(generation, hashB)
    val remaining = withLaterArrival.consume(captured.hashes)

    expect.same(Set(hashB), remaining.hashes)
  }
}
