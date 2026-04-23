package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionReason, EvictionVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerEvictionVote
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Concrete [[EvictionVoter]] that signs an `EvictionVote` with the local keypair, stores it locally, gossips it to the current facilitator
  * set as a `ConsensusPeerVote`, and offers a `CheckEvictionAssembly` command on the consensus queue so the state transition can attempt
  * certificate assembly.
  *
  * Mirrors `GossipingViewChangeVoter`. The `lastSnapshotHashOf` function pulls the last committed snapshot hash out of the generic
  * `Outcome` — layer-specific (e.g., `_.finished.snapshotHash` in dag-l0 and currency-l0). All other construction is generic.
  *
  * Emission-gate logic (committee membership, per-round cap, `clusterStorage` responsiveness) is the caller's responsibility — typically
  * `StallDetector` when a peer meets the silent-for-N-cycles criterion. This voter trusts the caller and unconditionally signs + gossips.
  */
class GossipingEvictionVoter[F[
  _
]: Async: HasherSelector: SecurityProvider, Event, Key: TypeTag: Encoder, Artifact, Ctx, Status, Outcome, Kind](
  selfId: PeerId,
  keyPair: KeyPair,
  gossip: Gossip[F],
  storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  lastSnapshotHashOf: Outcome => Hash,
  logger: SelfAwareStructuredLogger[F]
) extends EvictionVoter[F, Key] {

  // Codex corrective #4 (B2 re-admission design): probation peers are NOT B1-evictable
  // because the state creator excludes `readmissionCountdown` peers from `state.facilitators`
  // at committee-formation time (see GlobalSnapshotConsensusStateCreator / its currency-l0
  // mirror). `candidates` in StallDetector is constrained to the committee; a peer not in
  // the committee can never be a target. No explicit guard is needed here — the invariant
  // is upheld at the state-creator layer.
  def emitEvictionVote(
    key: Key,
    target: PeerId,
    reason: EvictionReason
  ): F[Unit] =
    storage.getState(key).flatMap {
      case None =>
        ConsensusLog.warn(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          LogEvent.Eviction,
          "skipped" -> "no_state",
          "target" -> ConsensusLog.pid(target)
        )
      case Some(state) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          // Canonical committee hash for vote content so every voter signs the
          // same hash value — EvictionCertificateBuilder matches votes by this
          // field. Using mutable state.facilitators would block cert assembly
          // when different voters captured different withdrawal timings.
          state.roundStartFacilitators.value.hash.flatMap { facilitatorsHash =>
            val lastSnapshotHash = lastSnapshotHashOf(state.lastOutcome)
            val vote = EvictionVote(target, reason, facilitatorsHash, lastSnapshotHash)
            vote.sign(keyPair).flatMap { signedVote =>
              // Spread to live peers only — gossip delivery target, not vote content.
              val targets = state.facilitators.value.toSet - selfId
              storage.addEvictionVote(selfId, key, signedVote) >>
                gossip.spreadDirect(ConsensusPeerEvictionVote[Key](key, signedVote), targets) >>
                ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    key.toString,
                    "n/a",
                    LogEvent.Eviction,
                    "emitted" -> "eviction_vote",
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
