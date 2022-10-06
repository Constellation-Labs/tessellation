package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.effect._
import cats.effect.kernel.Clock
import cats.effect.std.Supervisor
import cats.syntax.applicative._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.semigroup._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Order, Show}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensus
import org.tessellation.sdk.domain.healthcheck.consensus.types.{ConsensusRounds, HealthCheckRoundId}
import org.tessellation.sdk.infrastructure.consensus._
import org.tessellation.sdk.infrastructure.consensus.declaration.PeerDeclaration

import io.circe.{Decoder, Encoder}

object PeerDeclarationHealthCheck {

  def make[F[_]: Async: GenUUID, K: Order: TypeTag: Encoder: Decoder: Show, A](
    clusterStorage: ClusterStorage[F],
    selfId: PeerId,
    gossip: Gossip[F],
    timeTriggerInterval: FiniteDuration,
    config: HealthCheckConfig,
    consensusStorage: ConsensusStorage[F, _, K, A],
    consensusManager: ConsensusManager[F, K, A],
    httpClient: PeerDeclarationHttpClient[F, K]
  )(implicit S: Supervisor[F]): F[HealthCheckConsensus[F, Key[K], Health, Status[K], Decision]] = {
    def mkWaitingProposals = Ref.of[F, Set[Status[K]]](Set.empty)
    def mkRounds =
      Ref.of[F, ConsensusRounds[F, Key[K], Health, Status[K], Decision]](ConsensusRounds(List.empty, Map.empty))

    (mkRounds, mkWaitingProposals).mapN { (rounds, waitingProposals) =>
      val driver = new PeerDeclarationHealthCheckDriver[K]()
      new HealthCheckConsensus[F, Key[K], Health, Status[K], Decision](
        clusterStorage,
        selfId,
        driver,
        gossip,
        config,
        waitingProposals
      ) {

        def allRounds: Ref[F, ConsensusRounds[F, Key[K], Health, Status[K], Decision]] = rounds

        def ownStatus(key: Key[K]): F[Fiber[F, Throwable, PeerDeclarationHealth]] = S.supervise(peerHealth(key))

        def statusOnError(key: Key[K]): PeerDeclarationHealth = TimedOut

        def requestProposal(peer: PeerId, roundIds: Set[HealthCheckRoundId], ownProposal: Status[K]): F[Option[Status[K]]] =
          clusterStorage
            .getPeer(peer)
            .flatMap(_.map(toP2PContext).traverse(httpClient.requestProposal(roundIds, ownProposal).run))
            .map(_.flatten)

        override def startOwnRound(key: Key[K]): F[Unit] =
          createRoundId.map(HealthCheckRoundId(_, selfId)).flatMap(id => startRound(key, Set(id)))

        def threshold(state: ConsensusState[K, A]) = state.facilitators.size / 2

        def periodic: F[Unit] =
          for {
            time <- Clock[F].monotonic
            states <- consensusStorage.getStates
            maybeOwnRegistration <- consensusStorage.getOwnRegistration
            roundKeys <- states
              .filter(_.facilitators.contains(selfId))
              .filter(state => maybeOwnRegistration.fold(false)(state.key >= _))
              .flatTraverse { state =>
                if (isTimedOut(state, time)) {
                  consensusStorage.getPeerDeclarations(state.key).map { peerDeclarations =>
                    def peersMissingDeclaration[B <: PeerDeclaration](
                      getter: PeerDeclarations => Option[B]
                    ): List[PeerId] =
                      state.facilitators.filter(peerId => peerDeclarations.get(peerId).flatMap(getter).isEmpty)

                    val missing = state.status match {
                      case _: CollectingFacilities[_] =>
                        peersMissingDeclaration(_.facility)
                          .map(PeerDeclarationHealthCheckKey(_, state.key, kind.Facility))
                      case _: CollectingProposals[_] =>
                        peersMissingDeclaration(_.proposal)
                          .map(PeerDeclarationHealthCheckKey(_, state.key, kind.Proposal))
                      case _: CollectingSignatures[_] =>
                        peersMissingDeclaration(_.signature)
                          .map(PeerDeclarationHealthCheckKey(_, state.key, kind.Signature))
                      case _: Finished[_] => List.empty[Key[K]]
                    }

                    if (missing.size <= threshold(state)) missing else List.empty[Key[K]]
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
                    logger.info(s"Outcome for key ${key.show}: positive - no action required | Round ids: ${roundIds.show}")
                  }
                case (NegativeOutcome, round) =>
                  round.getRoundIds.flatMap { roundIds =>
                    consensusStorage
                      .deregisterPeer(key.id, key.consensusKey)
                      .ifM(
                        logger.info(
                          s"Outcome for key ${key.show}: negative - node found and unregistered at given height. Removing facilitator | Round ids: ${roundIds.show}"
                        ),
                        logger.info(
                          s"Outcome for key ${key.show}: negative - node not found as registered at given height. Removing facilitator | Round ids: ${roundIds.show}"
                        )
                      ) >>
                      consensusStorage.addRemovedFacilitator(key.consensusKey, key.id) >>=
                      consensusManager.checkForStateUpdateSync(key.consensusKey)
                  }
              }
          }.void

        private def peerHealth(key: Key[K]): F[Health] =
          for {
            time <- Clock[F].monotonic
            maybeState <- consensusStorage.getState(key.consensusKey)
            maybePeerDeclaration <- consensusStorage.getPeerDeclarations(key.consensusKey).map(_.get(key.id))
            maybeOwnRegistration <- consensusStorage.getOwnRegistration
            health = maybePeerDeclaration.flatMap { pd =>
              key.kind match {
                case kind.Facility  => pd.facility
                case kind.Proposal  => pd.proposal
                case kind.Signature => pd.signature
              }
            }.map(_ => Received).getOrElse {
              maybeState
                .filter(_.facilitators.contains(key.id))
                .filter(state => maybeOwnRegistration.fold(false)(state.key >= _))
                .filter { state =>
                  (key.kind, state.status) match {
                    case (kind.Facility, _: CollectingFacilities[_])  => true
                    case (kind.Proposal, _: CollectingProposals[_])   => true
                    case (kind.Signature, _: CollectingSignatures[_]) => true
                    case _                                            => false
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

        private def isTimedOut(state: ConsensusState[K, _], time: FiniteDuration): Boolean =
          state.status match {
            case CollectingFacilities(Some(FacilityInfo(_, None))) =>
              time > (state.statusUpdatedAt |+| config.peerDeclaration.receiveTimeout |+| timeTriggerInterval)
            case _ => time > (state.statusUpdatedAt |+| config.peerDeclaration.receiveTimeout)
          }
      }

    }
  }
}
