package io.constellationnetwork.dag.l0.infrastructure.snapshot

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object GlobalSnapshotEventsPublisherDaemonSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  private val peerA = peer('a')
  private val peerB = peer('b')
  private val peerC = peer('c')
  private val formerPeer = peer('d')
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
}
