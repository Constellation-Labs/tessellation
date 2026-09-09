package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.globalSnapshotSync.{
  GlobalSnapshotSync,
  GlobalSnapshotSyncOrdinal,
  GlobalSnapshotSyncReference
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.RecoveryGlobalSnapshotSync._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object RecoveryGlobalSnapshotSyncSuite extends SimpleIOSuite {

  private def peer(value: Int): PeerId = PeerId(Hex(s"peer$value".padTo(64, '0')))
  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)
  private def session(value: Long): SessionToken = SessionToken(Generation(PosLong.unsafeFrom(value)))
  private def reference(value: Long): GlobalSnapshotSyncReference =
    GlobalSnapshotSyncReference(GlobalSnapshotSyncOrdinal(NonNegLong.unsafeFrom(value)), Hash(s"sync-$value"))

  test("refresh classification preserves ordinary empty and self-only semantics") {
    val self = peer(1)

    IO.pure(
      expect.same(ChainStart, classify(self, SortedMap.empty)) &&
        expect.same(Chained(reference(7L)), classify(self, SortedMap(self -> reference(7L))))
    )
  }

  test("an inherited peer other than self requires atomic reset") {
    val self = peer(1)
    val inherited = SortedMap(self -> reference(7L), peer(2) -> reference(8L))

    IO.pure(expect.same(ResetInheritedMultiPeerView, classify(self, inherited)))
  }

  test("MinValue from a newly admitted signer is ordinary unless that signer is the entire authoritative set") {
    val signer = peer(1)
    val inherited = Set(peer(2), peer(3))

    IO.pure(
      expect(!hasResetShape(signer, GlobalSnapshotSyncOrdinal.MinValue, inherited, Set(signer, peer(4)))) &&
        expect(hasResetShape(signer, GlobalSnapshotSyncOrdinal.MinValue, inherited, Set(signer)))
    )
  }

  private def validContext(
    signer: PeerId,
    oldestRetained: Long = 51L,
    lastAccepted: Long = 10L
  ): ValidationContext =
    ValidationContext(
      currentSigners = Set(signer),
      inheritedPeerIds = Set(signer, peer(2)),
      inheritedSessions = SortedMap(signer -> session(1L)),
      currentGlobalParent = ordinal(100L),
      recentGlobalSnapshots = SortedMap.from((oldestRetained to 100L).map(o => ordinal(o) -> Hash(s"global-$o"))),
      retainedCount = 50,
      syncOffset = 2L,
      metagraphLastAcceptedOn = ordinal(lastAccepted),
      unappliedGlobalChangeOrdinals = SortedSet.empty,
      snapshotProtocolV1ActivationOrdinal = ordinal(51L)
    )

  private def reset(anchor: Long, sessionValue: Long = 2L): GlobalSnapshotSync =
    GlobalSnapshotSync(GlobalSnapshotSyncOrdinal.MinValue, ordinal(anchor), Hash(s"global-$anchor"), session(sessionValue))

  test("valid reset is bound to singleton signer, recent anchor, derived target, dormancy, and empty unapplied state") {
    val signer = peer(1)
    IO.pure(expect.same(Right(()), validateReset(signer, reset(100L), validContext(signer))))
  }

  test("derived selected target may equal the inclusive oldest retained ordinal") {
    val signer = peer(1)
    IO.pure(expect.same(Right(()), validateReset(signer, reset(53L), validContext(signer))))
  }

  test("an in-window anchor is rejected when sync offset puts its selected target outside retention") {
    val signer = peer(1)
    val context = validContext(signer).copy(snapshotProtocolV1ActivationOrdinal = SnapshotOrdinal.MinValue)
    IO.pure(
      expect.same(
        Left(ResetSelectedTargetOutsideRetainedWindow),
        validateReset(signer, reset(52L), context)
      )
    )
  }

  test("a reset selected target cannot precede snapshot protocol v1 activation") {
    val signer = peer(1)
    val context = validContext(signer).copy(snapshotProtocolV1ActivationOrdinal = ordinal(99L))

    IO.pure(expect.same(Left(ResetBeforeSnapshotProtocolV1Activation), validateReset(signer, reset(100L), context)))
  }

  test("an absent MaxValue protocol activation never authorizes a recovery reset") {
    val signer = peer(1)
    val context = validContext(signer).copy(snapshotProtocolV1ActivationOrdinal = SnapshotOrdinal.MaxValue)

    IO.pure(expect.same(Left(ResetBeforeSnapshotProtocolV1Activation), validateReset(signer, reset(100L), context)))
  }

  test("a locally cached snapshot ahead of the consensus parent cannot authorize a reset") {
    val signer = peer(1)
    val ahead = validContext(signer).copy(
      recentGlobalSnapshots = validContext(signer).recentGlobalSnapshots.updated(ordinal(101L), Hash("global-101"))
    )

    IO.pure(expect.same(Left(ResetAnchorAfterCurrentGlobalParent), validateReset(signer, reset(101L), ahead)))
  }

  test("reset fails when the signed/current signer set is not exactly the reset signer") {
    val signer = peer(1)
    val context = validContext(signer).copy(currentSigners = Set(signer, peer(3)))
    IO.pure(expect.same(Left(CurrentSignerSetNotSingleton), validateReset(signer, reset(100L), context)))
  }

  test("reset fails for a self-only inherited view") {
    val signer = peer(1)
    val context = validContext(signer).copy(inheritedPeerIds = Set(signer))
    IO.pure(expect.same(Left(InheritedViewIsNotMultiPeerForSigner), validateReset(signer, reset(100L), context)))
  }

  test("reset session must strictly increase when the signer had an inherited declaration") {
    val signer = peer(1)
    IO.pure(expect.same(Left(ResetSessionIsNotNewer), validateReset(signer, reset(100L, 1L), validContext(signer))))
  }

  test("unapplied changes and a non-dormant lineage each fail closed") {
    val signer = peer(1)
    val unapplied = validContext(signer).copy(unappliedGlobalChangeOrdinals = SortedSet(ordinal(90L)))
    val active = validContext(signer, lastAccepted = 80L)

    IO.pure(
      expect.same(Left(MetagraphHasUnappliedGlobalChanges), validateReset(signer, reset(100L), unapplied)) &&
        expect.same(Left(MetagraphLineageIsNotDormant), validateReset(signer, reset(100L), active))
    )
  }
}
