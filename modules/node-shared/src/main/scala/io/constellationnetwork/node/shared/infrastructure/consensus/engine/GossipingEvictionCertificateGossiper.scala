package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionCertificate
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerEvictionCertificate
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, ConsensusStorage, _}
import io.constellationnetwork.schema.peer.PeerId

import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Concrete [[EvictionCertificateGossiper]] that broadcasts an assembled cert to the current facilitator set via `gossip.spreadDirect`,
  * mirroring the per-vote emission in [[GossipingEvictionVoter]].
  *
  * The cert is already quorum-signed (it carries a `NonEmptySet[Signed[EvictionVote]]`) so no additional signing happens here — the
  * envelope is just routing. Receivers re-validate structurally before storing (see `RumorHandler.handleEvictionCertificate`).
  */
class GossipingEvictionCertificateGossiper[F[_]: Async, Event, Key: TypeTag: Encoder, Artifact, Ctx, Status, Outcome, Kind](
  selfId: PeerId,
  gossip: Gossip[F],
  storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  logger: SelfAwareStructuredLogger[F]
) extends EvictionCertificateGossiper[F, Key] {

  def gossipCert(
    key: Key,
    cert: EvictionCertificate
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
          "carrier" -> "cert_gossip",
          "target" -> ConsensusLog.pid(cert.targetPeer)
        )
      case Some(state) =>
        // Spread to live committee peers only; cert content is universal but delivery is targeted.
        // Excluding selfId because we already stored locally before calling gossip.
        val targets = state.facilitators.value.toSet - selfId
        if (targets.isEmpty)
          // Nothing to gossip to — committee shrunk to just self mid-round, which the round-monitor
          // will resolve. Still log so absence of broadcast is visible.
          ConsensusLog.debug(
            logger,
            Category.Phase,
            key.toString,
            "n/a",
            LogEvent.Eviction,
            "skipped" -> "no_targets",
            "carrier" -> "cert_gossip",
            "target" -> ConsensusLog.pid(cert.targetPeer)
          )
        else
          gossip.spreadDirect(ConsensusPeerEvictionCertificate[Key](key, cert), targets) >>
            ConsensusLog
              .info(
                logger,
                Category.Phase,
                key.toString,
                "n/a",
                LogEvent.Eviction,
                "emitted" -> "eviction_cert",
                "target" -> ConsensusLog.pid(cert.targetPeer),
                "votes" -> cert.votes.toSortedSet.size.toString,
                "targets" -> targets.size.toString
              )
              .void
    }
}
