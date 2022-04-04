package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random
import cats.kernel.Next
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.semigroupk._
import cats.{Eq, Order, Show}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.sdk.infrastructure.healthcheck.declaration.{
  PeerDeclarationHealthCheck,
  PeerDeclarationHealthCheckDaemon,
  PeerDeclarationProposalHandler
}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

object Consensus {

  def make[
    F[_]: Async: Random: KryoSerializer: SecurityProvider,
    Event <: AnyRef: TypeTag: ClassTag,
    Key: Show: Order: Next: TypeTag: ClassTag,
    Artifact <: AnyRef: Show: Eq: TypeTag
  ](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    clusterStorage: ClusterStorage[F],
    healthCheckConfig: HealthCheckConfig,
    initKeyAndArtifact: Option[(Key, Signed[Artifact])] = none
  ): F[Consensus[F, Event, Key, Artifact]] =
    for {
      storage <- ConsensusStorage.make[F, Event, Key, Artifact](initKeyAndArtifact)
      healthCheck <- PeerDeclarationHealthCheck.make[F, Key](
        clusterStorage,
        selfId,
        gossip,
        healthCheckConfig,
        storage
      )
      stateUpdater = ConsensusStateUpdater.make[F, Event, Key, Artifact](
        consensusFns,
        storage,
        clusterStorage,
        gossip,
        keyPair,
        selfId
      )
      manager <- ConsensusManager.make[F, Event, Key, Artifact](
        clusterStorage,
        consensusFns,
        storage,
        stateUpdater
      )
      handler = ConsensusHandler.make[F, Event, Key, Artifact](storage, manager) <+>
        PeerDeclarationProposalHandler.make[F, Key](healthCheck)
      daemon = PeerDeclarationHealthCheckDaemon.make(healthCheck, healthCheckConfig)

    } yield new Consensus(handler, storage, daemon)
}

sealed class Consensus[F[_]: Async, Event, Key, Artifact] private (
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact],
  val daemon: Daemon[F]
) {}
