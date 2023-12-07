package org.tessellation.node.shared.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.consensus.message.ConsensusEvent

import fs2.Stream
import io.circe.Encoder

trait SnapshotEventsPublisherDaemon[F[_]] {
  def spawn: Daemon[F]

}

object SnapshotEventsPublisherDaemon {
  def make[F[_]: Async: Supervisor, E: TypeTag: Encoder](
    gossip: Gossip[F],
    consensusEvents: Stream[F, E]
  ): SnapshotEventsPublisherDaemon[F] =
    new SnapshotEventsPublisherDaemon[F] {
      def spawn: Daemon[F] = Daemon.spawn {
        consensusEvents
          .map(ConsensusEvent(_))
          .evalMap(gossip.spread[ConsensusEvent[E]])
          .compile
          .drain
      }
    }
}
