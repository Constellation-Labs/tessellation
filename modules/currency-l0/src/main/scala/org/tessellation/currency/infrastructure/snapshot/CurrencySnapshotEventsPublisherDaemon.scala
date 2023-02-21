package org.tessellation.currency.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}

import org.tessellation.currency.schema.currency.CurrencyBlock
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.message.ConsensusEvent
import org.tessellation.security.signature.Signed

import fs2.Stream

object CurrencySnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    l1OutputQueue: Queue[F, Signed[CurrencyBlock]],
    gossip: Gossip[F]
  ): Daemon[F] = Daemon.spawn {
    Stream
      .fromQueueUnterminated(l1OutputQueue)
      .map(ConsensusEvent(_))
      .evalMap(gossip.spread[ConsensusEvent[CurrencySnapshotEvent]])
      .compile
      .drain
  }

}
