package org.tessellation.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.all._

import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.message.ConsensusEvent

import fs2._

object GlobalSnapshotEventsPublisherDaemon {

  def make[F[_]: Async](
    stateChannelOutputs: Queue[F, StateChannelOutput],
    dagEvents: Stream[F, DAGEvent],
    gossip: Gossip[F]
  ): Daemon[F] = Daemon.spawn {
    Stream
      .fromQueueUnterminated(stateChannelOutputs)
      .mapFilter(_.takeGistedOutputOf[StateChannelSnapshot])
      .map(_.asLeft[DAGEvent])
      .merge(
        dagEvents
          .map(_.asRight[StateChannelEvent])
      )
      .map(ConsensusEvent(_))
      .evalMap(gossip.spread[ConsensusEvent[GlobalSnapshotEvent]])
      .compile
      .drain
  }

}
