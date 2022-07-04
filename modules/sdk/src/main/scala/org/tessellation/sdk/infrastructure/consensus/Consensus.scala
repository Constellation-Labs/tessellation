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
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.sdk.infrastructure.healthcheck.declaration._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import io.circe.{Decoder, Encoder}
import org.http4s.client.Client

object Consensus {

  def make[
    F[_]: Async: Random: KryoSerializer: SecurityProvider,
    Event <: AnyRef: TypeTag: ClassTag,
    K: Show: Order: Next: TypeTag: ClassTag: Encoder: Decoder,
    Artifact <: AnyRef: Show: Eq: TypeTag
  ](
    consensusFns: ConsensusFunctions[F, Event, K, Artifact],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    timeTriggerInterval: FiniteDuration,
    whitelisting: Option[Set[PeerId]],
    clusterStorage: ClusterStorage[F],
    healthCheckConfig: HealthCheckConfig,
    client: Client[F],
    session: Session[F],
    initKeyAndArtifact: Option[(K, Signed[Artifact])] = none
  ): F[Consensus[F, Event, K, Artifact]] =
    for {
      storage <- ConsensusStorage.make[F, Event, K, Artifact](initKeyAndArtifact)
      stateUpdater = ConsensusStateUpdater.make[F, Event, K, Artifact](
        consensusFns,
        storage,
        clusterStorage,
        gossip,
        whitelisting,
        keyPair,
        selfId
      )
      manager = ConsensusManager.make[F, Event, K, Artifact](
        timeTriggerInterval,
        storage,
        stateUpdater
      )
      httpClient = PeerDeclarationHttpClient.make[F, K](client, session)
      healthCheck <- PeerDeclarationHealthCheck.make[F, K, Artifact](
        clusterStorage,
        selfId,
        gossip,
        timeTriggerInterval,
        healthCheckConfig,
        storage,
        manager,
        httpClient
      )
      handler = ConsensusHandler.make[F, Event, K, Artifact](storage, manager, consensusFns, whitelisting) <+>
        PeerDeclarationProposalHandler.make[F, K](healthCheck)
      daemon = PeerDeclarationHealthCheckDaemon.make(healthCheck, healthCheckConfig)

    } yield new Consensus(handler, storage, manager, daemon, healthCheck)
}

sealed class Consensus[F[_]: Async, Event, K, Artifact] private (
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, K, Artifact],
  val manager: ConsensusManager[F, K, Artifact],
  val daemon: Daemon[F],
  val healthcheck: HealthCheckConsensus[F, Key[K], Health, Status[K], Decision]
) {}
