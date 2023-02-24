package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}

import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon

import fs2.Stream

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    gossip: Gossip[F]
  ): Daemon[F] = {
    val events: Stream[F, CurrencySnapshotEvent] = Stream.fromQueueUnterminated(l1OutputQueue)

    SnapshotEventsPublisherDaemon
      .make(
        gossip,
        events
      )
      .spawn
  }
}
