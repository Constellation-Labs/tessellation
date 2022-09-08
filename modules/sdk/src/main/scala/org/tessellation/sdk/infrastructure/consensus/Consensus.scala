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

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensus
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.sdk.infrastructure.healthcheck.declaration.{Key => HealthCheckKey, _}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.client.Client

object Consensus {

  def make[
    F[_]: Async: Random: KryoSerializer: SecurityProvider,
    Event <: AnyRef: TypeTag: ClassTag,
    Key: Show: Order: Next: TypeTag: ClassTag: Encoder: Decoder,
    Artifact <: AnyRef: Show: Eq: TypeTag
  ](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    timeTriggerInterval: FiniteDuration,
    seedlist: Option[Set[PeerId]],
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    healthCheckConfig: HealthCheckConfig,
    client: Client[F],
    session: Session[F],
    initKeyAndArtifact: Option[(Key, Option[Signed[Artifact]])] = none
  ): F[Consensus[F, Event, Key, Artifact]] =
    for {
      storage <- ConsensusStorage.make[F, Event, Key, Artifact](initKeyAndArtifact)
      stateUpdater = ConsensusStateUpdater.make[F, Event, Key, Artifact](
        consensusFns,
        storage,
        gossip,
        seedlist,
        keyPair,
        selfId
      )
      consClient = ConsensusClient.make[F, Key](client, session)
      manager <- ConsensusManager.make[F, Event, Key, Artifact](
        timeTriggerInterval,
        storage,
        stateUpdater,
        nodeStorage,
        clusterStorage,
        consClient,
        gossip
      )
      httpClient = PeerDeclarationHttpClient.make[F, Key](client, session)
      healthCheck <- PeerDeclarationHealthCheck.make[F, Key, Artifact](
        clusterStorage,
        selfId,
        gossip,
        timeTriggerInterval,
        healthCheckConfig,
        storage,
        manager,
        httpClient
      )
      handler = ConsensusHandler.make[F, Event, Key, Artifact](storage, manager, consensusFns) <+>
        PeerDeclarationProposalHandler.make[F, Key](healthCheck)
      daemon = PeerDeclarationHealthCheckDaemon.make(healthCheck, healthCheckConfig)

      routes = new ConsensusRoutes[F, Key](storage)

    } yield new Consensus(handler, storage, manager, daemon, healthCheck, routes.routes)
}

sealed class Consensus[F[_]: Async, Event, Key, Artifact] private (
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact],
  val manager: ConsensusManager[F, Key, Artifact],
  val daemon: Daemon[F],
  val healthcheck: HealthCheckConsensus[F, HealthCheckKey[Key], Health, Status[Key], Decision],
  val p2pRoutes: HttpRoutes[F]
) {}
