package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{AdmissionReason, AdmissionVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerAdmissionVote
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Concrete [[AdmissionVoter]] that signs an `AdmissionVote` with the local keypair, stores it locally, gossips it to the current
  * facilitator set, and offers a `CheckAdmissionAssembly` command on the consensus queue (B2).
  *
  * Mirrors [[GossipingEvictionVoter]]. Uses `roundStartFacilitators` (canonical) for the vote's `facilitatorsHash` so every honest voter
  * signs the same hash regardless of mid-round withdrawals — cert assembly requires matching hashes across votes.
  */
class GossipingAdmissionVoter[F[
  _
]: Async: HasherSelector: SecurityProvider, Event, Key: TypeTag: Encoder, Artifact, Ctx, Status, Outcome, Kind](
  selfId: PeerId,
  keyPair: KeyPair,
  gossip: Gossip[F],
  storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  lastSnapshotHashOf: Outcome => Hash,
  logger: SelfAwareStructuredLogger[F]
) extends AdmissionVoter[F, Key] {

  def emitAdmissionVote(
    key: Key,
    target: PeerId,
    reason: AdmissionReason
  ): F[Unit] =
    storage.getState(key).flatMap {
      case None =>
        ConsensusLog.warn(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          LogEvent.Admission,
          "skipped" -> "no_state",
          "target" -> ConsensusLog.pid(target)
        )
      case Some(state) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          // Canonical committee hash — same rationale as GossipingEvictionVoter.
          state.roundStartFacilitators.value.hash.flatMap { facilitatorsHash =>
            val lastSnapshotHash = lastSnapshotHashOf(state.lastOutcome)
            val vote = AdmissionVote(target, reason, facilitatorsHash, lastSnapshotHash)
            vote.sign(keyPair).flatMap { signedVote =>
              val targets = state.facilitators.value.toSet - selfId
              storage.addAdmissionVote(selfId, key, signedVote) >>
                gossip.spreadDirect(ConsensusPeerAdmissionVote[Key](key, signedVote), targets) >>
                ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    key.toString,
                    "n/a",
                    LogEvent.Admission,
                    "emitted" -> "admission_vote",
                    "target" -> ConsensusLog.pid(target),
                    "reason" -> reason.toString,
                    "targets" -> targets.size.toString
                  )
                  .void
            }
          }
        }
    }
}
