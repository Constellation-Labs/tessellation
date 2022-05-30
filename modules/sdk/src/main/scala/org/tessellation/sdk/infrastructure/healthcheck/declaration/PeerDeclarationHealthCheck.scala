package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.effect._
import cats.effect.kernel.Clock
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensus
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds
import org.tessellation.sdk.infrastructure.consensus._
import org.tessellation.sdk.infrastructure.consensus.declaration.PeerDeclaration

object PeerDeclarationHealthCheck {

  def make[F[_]: Async: GenUUID, K: TypeTag, A](
    clusterStorage: ClusterStorage[F],
    selfId: PeerId,
    gossip: Gossip[F],
    config: HealthCheckConfig,
    consensusStorage: ConsensusStorage[F, _, K, A],
    consensusManager: ConsensusManager[F, _, K, A]
  ): F[HealthCheckConsensus[F, Key[K], Health, Status[K], Decision]] =
    Ref.of[F, ConsensusRounds[F, Key[K], Health, Status[K], Decision]](ConsensusRounds(List.empty, Map.empty)).map {
      rounds =>
        val driver = new PeerDeclarationHealthCheckDriver[K]()
        new HealthCheckConsensus[F, Key[K], Health, Status[K], Decision](clusterStorage, selfId, driver, gossip, config) {

          def allRounds: Ref[F, ConsensusRounds[F, Key[K], Health, Status[K], Decision]] = rounds

          def ownStatus(key: Key[K]): F[Fiber[F, Throwable, PeerDeclarationHealth]] = Spawn[F].start(peerHealth(key))

          def statusOnError(key: Key[K]): PeerDeclarationHealth = TimedOut

          def periodic: F[Unit] =
            for {
              time <- Clock[F].realTime
              states <- consensusStorage.getStates
              roundKeys <- states.flatTraverse { state =>
                if (isTimedOut(state, time)) {
                  consensusStorage.getPeerDeclarations(state.key).map { peerDeclarations =>
                    def peersMissingDeclaration[B <: PeerDeclaration](
                      getter: PeerDeclarations => Option[B]
                    ): List[PeerId] =
                      state.facilitators.filter(peerId => peerDeclarations.get(peerId).flatMap(getter).isEmpty)

                    state.status match {
                      case _: Facilitated[_] =>
                        peersMissingDeclaration(_.facility)
                          .map(PeerDeclarationHealthCheckKey(_, state.key, kind.Facility))
                      case _: ProposalMade[_] =>
                        peersMissingDeclaration(_.proposal)
                          .map(PeerDeclarationHealthCheckKey(_, state.key, kind.Proposal))
                      case _: MajoritySigned[_] =>
                        peersMissingDeclaration(_.signature)
                          .map(PeerDeclarationHealthCheckKey(_, state.key, kind.Signature))
                      case _: Finished[_] => List.empty[Key[K]]
                    }
                  }
                } else {
                  List.empty[Key[K]].pure[F]
                }
              }
              _ <- roundKeys.traverse(startOwnRound)
            } yield ()

          def onOutcome(outcomes: ConsensusRounds.Outcome[F, Key[K], Health, Status[K], Decision]): F[Unit] =
            outcomes.toList.traverse {
              case (key, t) =>
                t match {
                  case (PositiveOutcome, round) =>
                    round.getRoundIds.flatMap { roundIds =>
                      logger.info(s"Outcome for $roundIds for peer ${key.id}: positive - no action required")
                    }
                  case (NegativeOutcome, round) =>
                    round.getRoundIds.flatMap { roundIds =>
                      logger.info(s"Outcome for $roundIds for peer ${key.id}: negative - removing facilitator") >>
                        (consensusStorage.addRemovedFacilitator(key.consensusKey, key.id) >>=
                          consensusManager.checkForStateUpdateSync(key.consensusKey))
                    }
                }
            }.void

          private def peerHealth(key: Key[K]): F[Health] =
            for {
              time <- Clock[F].realTime
              maybeState <- consensusStorage.getState(key.consensusKey)
              maybePeerDeclaration <- consensusStorage.getPeerDeclarations(key.consensusKey).map(_.get(key.id))
              health = maybePeerDeclaration.flatMap { pd =>
                key.kind match {
                  case kind.Facility  => pd.facility
                  case kind.Proposal  => pd.proposal
                  case kind.Signature => pd.signature
                }
              }.map(_ => Received).getOrElse {
                maybeState
                  .filter(_.facilitators.contains(key.id))
                  .filter { state =>
                    (key.kind, state.status) match {
                      case (kind.Facility, _: Facilitated[_])     => true
                      case (kind.Proposal, _: ProposalMade[_])    => true
                      case (kind.Signature, _: MajoritySigned[_]) => true
                      case _                                      => false
                    }
                  }
                  .map { state =>
                    if (isTimedOut(state, time))
                      TimedOut
                    else
                      Awaiting
                  }
                  .getOrElse(NotRequired)
              }

            } yield health

          private def isTimedOut(state: ConsensusState[K, _], time: FiniteDuration) =
            time > (state.statusUpdatedAt |+| config.peerDeclaration.receiveTimeout)
        }

    }
}
