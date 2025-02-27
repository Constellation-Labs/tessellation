package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelOutput

import fs2.Stream

object GlobalSnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    stateChannelOutputs: Queue[F, StateChannelOutput],
    l1OutputQueue: Queue[F, Signed[Block]],
    allowSpendOutputQueue: Queue[F, Signed[AllowSpendBlock]],
    tokenLockOutputQueue: Queue[F, Signed[TokenLockBlock]],
    updateNodeParametersQueue: Queue[F, Signed[UpdateNodeParameters]],
    gossip: Gossip[F],
    consensusStorage: ConsensusStorage[F, GlobalSnapshotEvent, _, _, _, _, _, _]
  ): Daemon[F] = {
    val events: Stream[F, GlobalSnapshotEvent] = Stream
      .fromQueueUnterminated(stateChannelOutputs)
      .map(StateChannelEvent(_))
      .merge(
        Stream
          .fromQueueUnterminated(l1OutputQueue)
          .map(DAGEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(allowSpendOutputQueue)
          .map(AllowSpendEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(tokenLockOutputQueue)
          .map(TokenLockEvent(_))
      )
      .merge(
        Stream
          .fromQueueUnterminated(updateNodeParametersQueue)
          .map(UpdateNodeParametersEvent(_))
      )

    SnapshotEventsPublisherDaemon
      .make(
        gossip,
        events,
        consensusStorage
      )
      .spawn
  }

}
