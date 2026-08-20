package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.data.NonEmptySet
import cats.effect.IO

import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncOrdinal}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.RecoveryGlobalSnapshotSync.ResetInheritedMultiPeerView
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

object LastSentGlobalSnapshotSyncStorageSuite extends SimpleIOSuite {

  test("required recovery refresh remains armed until explicitly cleared") {
    val sync = Signed(
      GlobalSnapshotSync(
        GlobalSnapshotSyncOrdinal.MinValue,
        SnapshotOrdinal.unsafeApply(100L),
        Hash("global-100"),
        SessionToken(Generation(PosLong.unsafeFrom(2L)))
      ),
      NonEmptySet.one(SignatureProof(Id(Hex("peer")), Signature(Hex("signature"))))
    )
    val required = RequiredRecoveryRefresh(sync, ResetInheritedMultiPeerView, SnapshotOrdinal.unsafeApply(147L))

    for {
      implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      storage <- LastSentGlobalSnapshotSyncStorage.make[IO]()
      before <- storage.getRequiredRecoveryRefresh
      _ <- storage.armRecoveryRefresh(required)
      armed <- storage.getRequiredRecoveryRefresh
      _ <- storage.clearRequiredRecoveryRefresh
      cleared <- storage.getRequiredRecoveryRefresh
    } yield expect(before.isEmpty) && expect.same(Some(required), armed) && expect(cleared.isEmpty)
  }
}
