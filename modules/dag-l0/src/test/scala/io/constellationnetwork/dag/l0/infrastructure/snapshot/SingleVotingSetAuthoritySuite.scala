package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.IO

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object SingleVotingSetAuthoritySuite extends SimpleIOSuite {

  private def pid(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))
  private val key = SnapshotOrdinal.unsafeApply(42L)
  private val full = NonEmptySet.fromSetUnsafe(SortedSet(pid(1), pid(2), pid(3)))

  private def authority(core: NonEmptySet[PeerId]) =
    CertifiedConsensus.CertifiedRoundAuthorityV1(full, Hash.empty, core, Hash.empty)

  test("an inherited authority whose Core is the full committee is accepted") {
    GlobalSnapshotConsensusStateCreator
      .requireSingleVotingSetAuthority[IO](key, authority(full))
      .attempt
      .map(result => expect(result.isRight))
  }

  test("an inherited split Core/Tier-1 authority fails closed") {
    GlobalSnapshotConsensusStateCreator
      .requireSingleVotingSetAuthority[IO](key, authority(NonEmptySet.fromSetUnsafe(SortedSet(pid(1)))))
      .attempt
      .map(result => expect(result.left.exists(_.isInstanceOf[GlobalSnapshotConsensusStateCreator.SplitCertifiedAuthorityUnsupported])))
  }
}
