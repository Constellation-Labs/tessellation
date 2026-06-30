package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.effect.std.Queue

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.CheckUpdate
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._

/** Shared utility functions for consensus components.
  *
  * Contains helper methods used across multiple consensus classes to avoid duplication.
  *
  * ==Key Helpers==
  *
  * '''triggerUpdateIfChanged(key):''' After storing new data, checks if it changed anything and queues CheckUpdate if so.
  * {{{
  *   storage.addFacility(peerId, key, facility).flatMap { changed =>
  *     queue.offer(CheckUpdate(key)).whenA(changed)
  *   }
  * }}}
  */
object ConsensusHelpers {
  def triggerUpdateIfChanged[F[_]: Async, Key, Artifact, Ctx, Outcome](
    queue: Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]],
    key: Key
  )(result: Option[_]): F[Unit] =
    result.fold(Async[F].unit)(_ => queue.offer(CheckUpdate(key)))
}
