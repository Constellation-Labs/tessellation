package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object GlobalSnapshotFirstRoundAlignmentSuite extends SimpleIOSuite {

  private val peerA = PeerId(Hex("01" * 64))
  private val peerB = PeerId(Hex("02" * 64))
  private val key = SnapshotOrdinal(NonNegLong(101L))
  private val parentHash = Hash.fromBytes("normal-first-round-parent".getBytes("UTF-8"))
  private val facilitatorsHash = Hash.fromBytes("normal-first-round-facilitators".getBytes("UTF-8"))
  private val configHash = Hash.fromBytes("normal-first-round-config".getBytes("UTF-8"))

  private val facility = Facility(
    eventHashes = Set.empty,
    candidates = Candidates(Set.empty),
    trigger = EventTrigger.some,
    facilitatorsHash = facilitatorsHash,
    lastGlobalSnapshotOrdinal = key,
    lastSnapshotHash = parentHash,
    consensusConfigHash = configHash.some
  )

  pureTest("normal first-round committee stays disabled during true bootstrap") {
    val bootstrap = GlobalSnapshotConsensus.normalFirstRoundCommittee(
      List(peerA, peerB),
      recentProofSizes = List(1, 2, 2),
      bootstrapCompleteProofsThreshold = 3
    )
    val established = GlobalSnapshotConsensus.normalFirstRoundCommittee(
      List(peerB, peerA),
      recentProofSizes = List(1, 3),
      bootstrapCompleteProofsThreshold = 3
    )

    expect(bootstrap.isEmpty) && expect.same(Some(SortedSet(peerA, peerB)), established)
  }

  pureTest("normal first-round Facility pulse is bound to parent, committee, and config hashes") {
    def matches(candidate: Facility): Boolean =
      GlobalSnapshotConsensus.isNormalFirstRoundFacilityPulse(
        key,
        parentHash,
        facilitatorsHash,
        configHash,
        candidate
      )

    expect(matches(facility)) &&
    expect(matches(facility.copy(eventHashes = Set(Hash.empty), trigger = None))) &&
    expect(!matches(facility.copy(lastGlobalSnapshotOrdinal = SnapshotOrdinal(NonNegLong(102L))))) &&
    expect(!matches(facility.copy(lastSnapshotHash = Hash.empty))) &&
    expect(!matches(facility.copy(facilitatorsHash = Hash.empty))) &&
    expect(!matches(facility.copy(consensusConfigHash = Hash.empty.some))) &&
    expect(!matches(facility.copy(consensusConfigHash = None)))
  }
}
