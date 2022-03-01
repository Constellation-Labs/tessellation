package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.effect.Async
import cats.kernel.Next
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Eq, Order, Show}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.security.SecurityProvider

object Consensus {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider,
    Event <: AnyRef: TypeTag: ClassTag,
    Key: Show: Order: Next: TypeTag: ClassTag,
    Artifact <: AnyRef: Show: Eq: TypeTag
  ](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    clusterStorage: ClusterStorage[F],
    initKeyAndArtifact: Option[(Key, Artifact)] = none
  ): F[Consensus[F, Key, Artifact]] =
    ConsensusStorage.make[F, Event, Key, Artifact](initKeyAndArtifact).map { storage =>
      val stateUpdater = ConsensusStateUpdater.make[F, Event, Key, Artifact](
        consensusFns,
        storage,
        clusterStorage,
        gossip,
        keyPair,
        selfId
      )
      val manager = ConsensusManager.make[F, Event, Key, Artifact](
        consensusFns,
        storage,
        stateUpdater
      )
      val handler = ConsensusHandler.make[F, Event, Key, Artifact](storage, manager)

      new Consensus(handler, storage)
    }
}

sealed class Consensus[F[_], K, A] private (
  val handler: RumorHandler[F],
  storage: ConsensusStorage[F, _, K, A]
) {
  def setLastKeyAndArtifact = storage.setLastKeyAndArtifact(_)
}
