package org.tessellation.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensusValidator

object CurrencySnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    blockValidator: BlockValidator[F, CurrencyTransaction, CurrencyBlock],
    snapshotConfig: SnapshotConfig,
    environment: AppEnvironment,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F]
  ): F[
    SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]
  ] = {
    val currencySnapshotConsensuFunctions = CurrencySnapshotConsensusFunctions.make[F](
      BlockAcceptanceManager.make[F, CurrencyTransaction, CurrencyBlock](blockValidator),
      collateral
    )
    val currencySnapshotConsensusValidator = SnapshotConsensusValidator.make[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotStateProof,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext
    ](
      currencySnapshotConsensuFunctions
    )
    Consensus.make[F, CurrencySnapshotEvent, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext](
      stateChannelSnapshotService,
      currencySnapshotConsensuFunctions,
      currencySnapshotConsensusValidator,
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
