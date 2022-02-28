package org.tessellation.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.option._

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.security.SecurityProvider

object GlobalSnapshotConsensus {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    clusterStorage: ClusterStorage[F],
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    snapshotConfig: SnapshotConfig
  ): F[Consensus[F]] =
    Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact](
      GlobalSnapshotConsensusFunctions.make[F](globalSnapshotStorage, snapshotConfig.heightInterval),
      gossip,
      selfId,
      keyPair,
      clusterStorage,
      none
    )

}
