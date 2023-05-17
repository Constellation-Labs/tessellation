package org.tessellation.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.currency.{BaseDataApplicationL0Service, DataUpdate}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.{CurrencySnapshotAcceptanceManager, SnapshotConsensus}
import org.tessellation.security.SecurityProvider

import io.circe.Decoder
import io.circe.disjunctionCodecs._
import org.http4s.client.Client

object CurrencySnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    rewards: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot],
    snapshotConfig: SnapshotConfig,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    snapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]]
  ): F[
    SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]
  ] = {
    def noopDecoder: Decoder[DataUpdate] = Decoder.failedWithMessage[DataUpdate]("not implemented")

    implicit def daDecoder: Decoder[DataUpdate] = maybeDataApplication.map(_.dataDecoder).getOrElse(noopDecoder)

    Consensus.make[F, CurrencySnapshotEvent, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext](
      CurrencySnapshotConsensusFunctions.make[F](
        stateChannelSnapshotService,
        snapshotAcceptanceManager,
        collateral,
        rewards,
        maybeDataApplication
      ),
      gossip,
      selfId,
      keyPair,
      snapshotConfig.consensus,
      seedlist,
      clusterStorage,
      nodeStorage,
      client,
      session
    )
  }
}
