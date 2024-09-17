package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}

import org.tessellation.currency.dataApplication.{DataTransaction, _}
import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent

import fs2.Stream
import io.circe.{Encoder, Json}

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    gossip: Gossip[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]]
  ): Daemon[F] = {
    val events: Stream[F, CurrencySnapshotEvent] = Stream.fromQueueUnterminated(l1OutputQueue)

    def noopEncoder: Encoder[DataTransaction] = (a: DataTransaction) => Json.Null

    implicit def daEncoder: Encoder[DataTransaction] = maybeDataApplication.map { da =>
      implicit val dataUpdateEncoder: Encoder[DataUpdate] = da.dataEncoder
      DataTransaction.encoder
    }.getOrElse(noopEncoder)

    SnapshotEventsPublisherDaemon
      .make(
        gossip,
        events
      )
      .spawn
  }
}
