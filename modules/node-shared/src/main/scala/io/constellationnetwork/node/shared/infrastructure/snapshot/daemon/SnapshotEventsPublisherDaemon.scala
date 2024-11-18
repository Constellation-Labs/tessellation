package io.constellationnetwork.node.shared.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusEvent

import fs2.Stream
import io.circe.Encoder

trait SnapshotEventsPublisherDaemon[F[_]] {
  def spawn: Daemon[F]

}

object SnapshotEventsPublisherDaemon {
  def make[F[_]: Async: Supervisor, E: TypeTag: Encoder](
    gossip: Gossip[F],
    consensusEvents: Stream[F, E],
    consensusStorage: ConsensusStorage[F, E, _, _, _, _, _, _]
  ): SnapshotEventsPublisherDaemon[F] =
    new SnapshotEventsPublisherDaemon[F] {
      def spawn: Daemon[F] = Daemon.spawn {
        consensusEvents
          .evalFilterNot(consensusStorage.containsEvent)
          .map(ConsensusEvent(_))
          .evalMap(gossip.spread[ConsensusEvent[E]])
          .compile
          .drain
      }
    }
}
