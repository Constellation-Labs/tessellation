package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import java.security.KeyPair

import cats.Applicative
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, ViewChangeVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerVote
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Concrete [[ViewChangeVoter]] that signs a ViewChangeVote with the local keypair, stores it locally, and gossips it to the current
  * facilitator set as a `ConsensusPeerVote` (the signed wire wrapper — separate from ConsensusPeerDeclaration so the per-vote Signed proof
  * survives to the VCC assembly stage).
  *
  * The `lastSnapshotHashOf` function pulls the last committed snapshot hash out of the generic `Outcome` — layer-specific (e.g.,
  * `_.finished.snapshotHash` in dag-l0 and currency-l0). All other construction is generic.
  */
class GossipingViewChangeVoter[F[
  _
]: Async: HasherSelector: SecurityProvider, Event, Key: TypeTag: Encoder, Artifact, Ctx, Status, Outcome, Kind](
  selfId: PeerId,
  keyPair: KeyPair,
  gossip: Gossip[F],
  storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  lastSnapshotHashOf: Outcome => Hash,
  configuredFraction: Double,
  logger: SelfAwareStructuredLogger[F]
) extends ViewChangeVoter[F, Key] {

  def emitViewChangeVote(
    key: Key,
    fromView: Long,
    toView: Long,
    highestKnownQc: Option[ProposalQC]
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
          "fromView" -> fromView.toString,
          "toView" -> toView.toString
        )
      case Some(state) =>
        CertifiedConsensus
          .pacemakerVoteTargets(
            state.certifiedConsensusActive,
            selfId,
            state.roundStartFacilitators.value.toSet,
            state.coreFacilitators.value.toSet,
            state.facilitators.value.toSet
          )
          .fold(
            ConsensusLog
              .info(
                logger,
                Category.Phase,
                key.toString,
                "n/a",
                LogEvent.ViewChange,
                "skipped" -> "not_frozen_core",
                "fromView" -> fromView.toString,
                "toView" -> toView.toString
              )
              .void
          ) { targets =>
            HasherSelector[F].withCurrent { implicit hasher =>
              // Canonical committee hash: every node signs the VCV with the same
              // facilitatorsHash, so the ViewChangeCertificateBuilder can match
              // votes from nodes that observed different mid-round withdrawals.
              state.roundStartFacilitators.value.hash.flatMap { facilitatorsHash =>
                val lastSnapshotHash = lastSnapshotHashOf(state.lastOutcome)
                storage.getCertifiedVoteLock(key).flatMap { certifiedLock =>
                  CertifiedConsensus
                    .verifyPersistedLockedQc[F](
                      certifiedLock,
                      state.roundStartFacilitators.value.toSet,
                      state.coreFacilitators.value.toSet,
                      configuredFraction
                    )
                    .flatMap {
                      case Left(error) =>
                        ConsensusLog.error(
                          logger,
                          Category.Phase,
                          key.toString,
                          "n/a",
                          LogEvent.ViewChange,
                          "skipped" -> "invalid_persisted_certified_qc",
                          "error" -> error
                        )
                      case Right(highestKnownCertifiedQc) =>
                        val vote = ViewChangeVote(
                          fromView,
                          toView,
                          facilitatorsHash,
                          lastSnapshotHash,
                          highestKnownQc,
                          highestKnownCertifiedQc
                        )
                        vote.sign(keyPair).flatMap { signedVote =>
                          storage.addViewChangeVote(selfId, key, fromView, toView, signedVote) >>
                            gossip.spreadDirect(ConsensusPeerVote[Key](key, signedVote), targets) >>
                            ConsensusLog
                              .info(
                                logger,
                                Category.Phase,
                                key.toString,
                                "n/a",
                                LogEvent.ViewChange,
                                "emitted" -> "vcv",
                                "fromView" -> fromView.toString,
                                "toView" -> toView.toString,
                                "qcPresent" -> highestKnownQc.isDefined.toString,
                                "certifiedQcPresent" -> highestKnownCertifiedQc.isDefined.toString,
                                "targets" -> targets.size.toString
                              )
                              .void
                        }
                    }
                }
              }
            }
          }
    }
}

object GossipingViewChangeVoter {

  /** No-op fallback when the voter can't be constructed (e.g., test doubles). Prefer `ViewChangeVoter.noop` directly. */
  def noopApplicative[F[_]: Applicative, Key]: ViewChangeVoter[F, Key] = ViewChangeVoter.noop[F, Key]
}
