package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.FunSuite

/** Regression for forensic finding C-1.
  *
  * The committee projection takes only the frozen committee, the round ordinal, and config: there is no parameter through which a
  * leader-reported responder list, self-health hint, score, tier, or quality ratio could enter. The end-to-end metamorphic test lives in
  * GlobalCertifiedLineageReplaySuite; this suite pins the projection and schedule behavior.
  */
object C1ClosureSuite extends FunSuite {

  private def peer(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val committee: List[PeerId] = (1 to 7).map(peer).toList.reverse

  private val config = ConsensusConfig(10.seconds, 10.seconds, 100L, 10.seconds, EventCutterConfig(1024, 1024))

  private val projection = CertifiedRoundCommitteeProjector.project(ord(20L), committee, config)

  test("every seated member votes; input order and duplicates do not matter") {
    val shuffled = CertifiedRoundCommitteeProjector.project(ord(20L), committee.reverse ++ committee.take(2), config)
    expect.same(committee.sorted, projection.votingCommittee).and(expect.same(projection.votingCommittee, shuffled.votingCommittee))
  }

  test("the certificate quorum depends only on the committee size") {
    expect.same(
      QuorumPolicy.fromFraction(7, config.quorumThresholdFraction),
      QuorumPolicy.fromFraction(projection.votingCommittee.size, config.quorumThresholdFraction)
    )
  }

  test("every member receives a leader turn within one view cycle") {
    val leaders = (0L until committee.size.toLong).map(v => LeaderSchedule.leader(projection.votingCommittee, 20L, v))
    expect.same(committee.toSet, leaders.toSet)
  }

  test("the inherited certified authority projects to the same voting committee") {
    val authority = CertifiedConsensus.CertifiedRoundAuthorityV1(
      cats.data.NonEmptySet.fromSetUnsafe(scala.collection.immutable.SortedSet.from(committee)),
      io.constellationnetwork.security.hash.Hash.empty
    )
    expect.same(
      projection.votingCommittee,
      CertifiedRoundCommitteeProjector.fromCertifiedAuthority(ord(20L), authority, config).votingCommittee
    )
  }
}
