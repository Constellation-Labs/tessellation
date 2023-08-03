package org.tessellation.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.tessellation.schema.Block
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

import fs2.Stream
import io.circe.disjunctionCodecs._

object GlobalSnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    stateChannelOutputs: Queue[F, StateChannelOutput],
    l1OutputQueue: Queue[F, Signed[Block]],
    gossip: Gossip[F]
  ): Daemon[F] = {
    val events: Stream[F, GlobalSnapshotEvent] = Stream
      .fromQueueUnterminated(stateChannelOutputs)
      .map(_.asLeft[DAGEvent])
      .merge(
        Stream
          .fromQueueUnterminated(l1OutputQueue)
          .map(_.asRight[StateChannelEvent])
      )

    SnapshotEventsPublisherDaemon
      .make(
        gossip,
        events
      )
      .spawn
  }

}
