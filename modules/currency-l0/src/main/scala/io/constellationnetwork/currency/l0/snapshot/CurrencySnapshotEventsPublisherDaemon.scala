package io.constellationnetwork.currency.l0.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}

import io.constellationnetwork.currency.dataApplication.{DataTransaction, _}
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent

import fs2.Stream
import io.circe.{Encoder, Json}

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    l1OutputQueue: Queue[F, CurrencySnapshotEvent],
    gossip: Gossip[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    consensusStorage: ConsensusStorage[F, CurrencySnapshotEvent, _, _, _, _, _, _]
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
        events,
        consensusStorage
      )
      .spawn
  }
}
