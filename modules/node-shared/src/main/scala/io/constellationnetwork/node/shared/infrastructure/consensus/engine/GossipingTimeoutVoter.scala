package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, TimeoutReason, TimeoutVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerTimeoutVote
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger

class GossipingTimeoutVoter[F[
  _
]: Async: HasherSelector: SecurityProvider, Event, Key: TypeTag: Encoder, Artifact, Ctx, Status, Outcome, Kind](
  selfId: PeerId,
  keyPair: KeyPair,
  gossip: Gossip[F],
  storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  lastSnapshotHashOf: Outcome => Hash,
  logger: SelfAwareStructuredLogger[F]
) extends TimeoutVoter[F, Key] {

  def emitTimeoutVote(
    key: Key,
    fromView: Long,
    toView: Long,
    highestKnownQc: Option[ProposalQC],
    reason: TimeoutReason
  ): F[Unit] =
    storage.getState(key).flatMap {
      case None =>
        ConsensusLog.warn(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          LogEvent.ViewChange,
          "skipped" -> "no_state",
          "vote" -> "timeout",
          "fromView" -> fromView.toString,
          "toView" -> toView.toString
        )
      case Some(state) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          state.roundStartFacilitators.value.hash.flatMap { facilitatorsHash =>
            val lastSnapshotHash = lastSnapshotHashOf(state.lastOutcome)
            val vote = TimeoutVote(fromView, toView, facilitatorsHash, lastSnapshotHash, highestKnownQc, reason)
            vote.sign(keyPair).flatMap { signedVote =>
              val targets = state.facilitators.value.toSet - selfId
              storage.addTimeoutVote(selfId, key, fromView, toView, signedVote) >>
                gossip.spreadDirect(ConsensusPeerTimeoutVote[Key](key, signedVote), targets) >>
                ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    key.toString,
                    "n/a",
                    LogEvent.ViewChange,
                    "emitted" -> "timeout_vote",
                    "fromView" -> fromView.toString,
                    "toView" -> toView.toString,
                    "reason" -> reason.toString,
                    "qcPresent" -> highestKnownQc.isDefined.toString,
                    "targets" -> targets.size.toString
                  )
                  .void
            }
          }
        }
    }
}
