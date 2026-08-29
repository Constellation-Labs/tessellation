package io.constellationnetwork.currency.l0.snapshot.synchronous

import cats._
import cats.data.Ior.{Both, Right}
import cats.effect.std.Supervisor
import cats.effect.syntax.all._
import cats.effect.{Async, Clock, Temporal}
import cats.kernel.Next
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.snapshot.synchronous.message.GetConsensusOutcomeRequest
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState._
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.Peer.toP2PContext
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import monocle.Lens
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryDetails
import retry.RetryPolicies.{constantDelay, fullJitter, limitRetries}
import retry.syntax.all._

trait ConsensusManager[F[_], Key, Artifact, Context, Status, Outcome, Kind] {
  def registerForConsensus(observationKey: Key): F[Unit]

  def startFacilitatingAfterDownload(
    observationKey: Key,
    lastArtifact: Signed[Artifact],
    lastContext: Context
  )(
    beforeOutcomeInstall: Outcome => F[Unit],
    afterOutcomeInstall: F[Unit]
  ): F[Unit]

  def startFacilitatingAfterRollback(lastKey: Key, initialOutcome: Outcome): F[Unit]

  /** Clears a download observation that did not reach a valid hand-off. The download program owns the corresponding node-state transition
    * back to WaitingForDownload.
    */
  def abortObservation: F[Unit]

  def withdrawFromConsensus: F[Unit]

  def facilitateOnEvent: F[Unit]

  private[synchronous] def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact, Kind]): F[Unit]
}

object ConsensusManager {

  private[snapshot] final case class OutcomeCorroboration[Outcome](
    selected: Option[Outcome],
    threshold: Int,
    validResponses: Int,
    maxMatching: Int,
    distinctValidValues: Int,
    ambiguous: Boolean
  )

  /** Select one byte-/type-exact outcome only after the minimum cohort that the fixed-universe ACK rules can retain serves that same value.
    * The responses are authenticated by the P2P session; `validateObservedOutcome` separately binds every candidate to the public artifact,
    * context, key, proof envelope, and joining identity.
    *
    * For artifact proof set N the bound is ceil(N/2), matching the keep threshold `(N + 1) / 2`. A binary-phase contraction can
    * legitimately finish with exactly that many responders (for example 2 of the 4 earlier artifact signers), so strict majority would
    * strand a healthy contracted cohort. If two groups reach the bound in an even split, the result is ambiguous and fails closed. N=1/2
    * retains the permissioned small-cohort one-responder residual.
    */
  private[snapshot] def selectCorroboratedOutcome[Outcome: Eq](
    proofSignerCount: Int,
    responses: List[(PeerId, Outcome)]
  ): Option[Outcome] =
    analyzeCorroboratedOutcome(proofSignerCount, responses).selected

  private[snapshot] def analyzeCorroboratedOutcome[Outcome: Eq](
    proofSignerCount: Int,
    responses: List[(PeerId, Outcome)]
  ): OutcomeCorroboration[Outcome] = {
    val threshold = (proofSignerCount + 1) / 2
    val uniqueResponses = responses.groupBy(_._1).valuesIterator.flatMap(_.headOption.map(_._2)).toList
    val grouped = uniqueResponses.foldLeft(List.empty[(Outcome, Int)]) { (groups, outcome) =>
      groups.indexWhere { case (candidate, _) => candidate === outcome } match {
        case -1    => (outcome -> 1) :: groups
        case index => groups.updated(index, groups(index)._1 -> (groups(index)._2 + 1))
      }
    }

    val qualifying = grouped.collect { case (outcome, count) if proofSignerCount > 0 && count >= threshold => outcome }
    OutcomeCorroboration(
      selected = qualifying match {
        case outcome :: Nil => outcome.some
        case _              => none
      },
      threshold = threshold,
      validResponses = uniqueResponses.size,
      maxMatching = grouped.map(_._2).maxOption.getOrElse(0),
      distinctValidValues = grouped.size,
      ambiguous = qualifying.sizeCompare(1) > 0
    )
  }

  /** A peer-ahead observation only triggers local re-download; it never installs private consensus authority. Requiring a strict majority
    * of the frozen next-round authority prevents one faulty responder from repeatedly bouncing an honest Currency validator.
    */
  private[snapshot] def hasStrictAuthorityMajorityAhead[Key: Order](
    selfId: PeerId,
    localKey: Key,
    authority: Set[PeerId],
    observations: Map[PeerId, Option[Key]]
  ): Boolean = {
    val threshold = authority.size / 2 + 1
    val ahead = observations.count { case (peerId, maybeKey) => authority.contains(peerId) && maybeKey.exists(_ > localKey) }

    if (authority.size === 2 && authority.contains(selfId)) ahead === 1
    else authority.nonEmpty && ahead >= threshold
  }

  /** Preserve an installed immediate-successor attempt while the authority is only one finalized round ahead. That observation is
    * compatible with ordinary gossip/finalization lag because the local member may already have contributed the signatures that completed
    * the remote outcome. A finished generation remains protected only if its outcome authorizes this node for the next round. A strict
    * authority majority beyond the immediate successor proves any other local attempt is obsolete and permits re-download. With no
    * installed attempt, even a one-round lead is sufficient to re-anchor the local node.
    */
  private[snapshot] def preservePeerAheadGeneration(
    hasCurrentGeneration: Boolean,
    currentGenerationFinished: Boolean,
    currentOutcomeAuthorizesSelf: Boolean,
    authorityMajorityBeyondImmediateSuccessor: Boolean
  ): Boolean =
    (currentGenerationFinished && currentOutcomeAuthorizesSelf) ||
      (hasCurrentGeneration && !authorityMajorityBeyondImmediateSuccessor)

  def make[F[_]: Async: Metrics, Event, Key: Show: Order: Next, Artifact: Eq, Context: Eq, Status: Eq, Outcome: Eq, Kind](
    selfId: PeerId,
    config: ConsensusConfig,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateRemover: ConsensusStateRemover[F, Key, Event, Artifact, Context, Status, Outcome, Kind],
    consensusOps: ConsensusOps[Status, Kind],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Outcome],
    validateObservedOutcome: (Outcome, Key, Signed[Artifact], Context) => F[Boolean],
    isAuthorizedForNextRound: Outcome => Boolean,
    nextRoundAuthority: Outcome => Set[PeerId]
  )(
    implicit S: Supervisor[F],
    _artifact: Lens[Outcome, Signed[Artifact]],
    _context: Lens[Outcome, Context],
    _key: Lens[Outcome, Key],
    _trigger: Lens[Outcome, ConsensusTrigger]
  ): F[ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind]] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    val collectRegistrationRetryPolicy = limitRetries(3).join(fullJitter(2.seconds))
    val observationRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def collectRegistration(peer: Peer): F[Unit] =
      for {
        registrationResponse <- consensusClient.getRegistration.run(peer)
        maybeResult <- registrationResponse.maybeKey.traverse(consensusStorage.registerPeer(peer.id, _))
        _ <- (registrationResponse.maybeKey, maybeResult).traverseN {
          case (key, result) =>
            if (result)
              logger.info(s"Peer ${peer.id.show} registered at ${key.show}")
            else
              logger.warn(s"Peer ${peer.id.show} cannot be registered at ${key.show}")
        }
      } yield ()

    def reanchorWhenAuthorityMajorityIsAhead: F[Unit] =
      nodeStorage.getNodeState.flatMap { nodeState =>
        Applicative[F].whenA(Set[NodeState](Ready, WaitingForReady).contains(nodeState)) {
          consensusStorage.getLastConsensusOutcome.flatMap(_.traverse_ { localOutcome =>
            val localKey = _key.get(localOutcome)
            val authority = nextRoundAuthority(localOutcome)

            clusterStorage.getResponsivePeers
              .flatMap(
                _.toList
                  .filter(peer => peer.state === Ready && authority.contains(peer.id))
                  .pure[F]
              )
              .flatMap { peers =>
                // Self is not present in ClusterStorage's remote peer map. Each response is
                // session-authenticated; errors/timeouts count as no observation.
                peers.toList.traverse { peer =>
                  consensusClient.getLatestConsensusOutcome
                    .run(peer)
                    .attempt
                    .map(result => peer.id -> result.toOption.flatten.map(_key.get))
                }
              }
              .map(_.toMap)
              .flatMap { observations =>
                val authorityMajorityAhead = hasStrictAuthorityMajorityAhead(selfId, localKey, authority, observations)
                val authorityMajorityBeyondImmediateSuccessor =
                  hasStrictAuthorityMajorityAhead(selfId, localKey.next, authority, observations)

                Applicative[F].whenA(authorityMajorityAhead) {
                  consensusStorage
                    .abandonGenerationIfCurrent(localKey) { maybeState =>
                      val maybeFinishedOutcome = maybeState.flatMap(
                        consensusStateAdvancer.getConsensusOutcome(_).map { case (_, outcome) => outcome }
                      )

                      preservePeerAheadGeneration(
                        hasCurrentGeneration = maybeState.nonEmpty,
                        currentGenerationFinished = maybeFinishedOutcome.nonEmpty,
                        currentOutcomeAuthorizesSelf = maybeFinishedOutcome.exists(isAuthorizedForNextRound),
                        authorityMajorityBeyondImmediateSuccessor = authorityMajorityBeyondImmediateSuccessor
                      )
                    }
                    .flatMap { abandoned =>
                      Applicative[F].whenA(abandoned) {
                        Metrics[F].incrementCounter("dag_currency_consensus_peer_ahead_reanchor_total") >>
                          logger.warn(
                            s"A strict majority of the frozen Currency authority is ahead; re-entering download {localKey=${localKey.show}}"
                          ) >>
                          consensusStorage.clearTimeTrigger >>
                          consensusStorage.clearObservationKey >>
                          nodeStorage
                            .tryModifyStateGetResult(Set[NodeState](Ready, WaitingForReady), WaitingForDownload)
                            .void
                      }
                    }
                }
              }
          })
        }
      }

    val manager = new ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind] {

      def registerForConsensus(observationKey: Key): F[Unit] =
        consensusStorage
          .trySetObservationKey(observationKey)
          .ifM(
            nodeStorage.tryModifyState(NodeState.WaitingForObserving, NodeState.Observing) >>
              logger.info(s"Registered for consensus {registrationKey=${observationKey.next.show}"),
            new Throwable(
              s"Registration for consensus failed {registrationKey=${observationKey.next.show}. Already registered at different key."
            ).raiseError[F, Unit]
          )

      def startFacilitatingAfterDownload(
        key: Key,
        lastArtifact: Signed[Artifact],
        lastContext: Context
      )(
        beforeOutcomeInstall: Outcome => F[Unit],
        afterOutcomeInstall: F[Unit]
      ): F[Unit] =
        S.supervise {
          val preferredOutcomePeers = lastArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId).toSet

          def fetchCorroboratedOutcome: F[Option[Outcome]] =
            fetchConsensusOutcomes
              .retryingOnFailuresAndAllErrors(
                wasSuccessful = wasSuccessful,
                policy = observationRetryPolicy,
                onFailure = onFailure,
                onError = onError
              )

          def selectProofSignerPeers: F[List[Peer]] =
            clusterStorage.getResponsivePeers
              .flatMap(_.toList.filter(_.state === Ready).pure[F])
              .map(_.filter(peer => preferredOutcomePeers.contains(peer.id)))

          def fetchConsensusOutcome(peer: Peer): F[Option[Outcome]] =
            consensusClient
              .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
              .run(peer)

          def fetchConsensusOutcomes: F[Option[Outcome]] =
            selectProofSignerPeers.flatMap { peers =>
              peers
                .parTraverseN(8) { peer =>
                  fetchConsensusOutcome(peer).attempt.flatMap {
                    case scala.util.Right(Some(outcome)) =>
                      validateObservedOutcome(outcome, key, lastArtifact, lastContext)
                        .map(valid => (true, _key.get(outcome) =!= key, Option.when(valid)(peer.id -> outcome)))
                    case scala.util.Right(None) => (false, false, none[(PeerId, Outcome)]).pure[F]
                    case scala.util.Left(error) =>
                      logger
                        .debug(error)(s"Artifact-proof signer did not serve Currency outcome {peerId=${peer.id.show}, key=${key.show}}")
                        .as((false, false, none[(PeerId, Outcome)]))
                  }
                }
                .flatMap { responses =>
                  val served = responses.count(_._1)
                  val differentKey = responses.count(_._2)
                  val analysis = analyzeCorroboratedOutcome(
                    preferredOutcomePeers.size,
                    responses.flatMap(_._3)
                  )
                  val invalid = served - analysis.validResponses
                  val outcome =
                    if (analysis.selected.nonEmpty) "success"
                    else if (peers.isEmpty) "no_responsive"
                    else if (analysis.ambiguous) "ambiguous"
                    else if (differentKey > 0) "different_key"
                    else if (invalid > 0) "invalid"
                    else "under_threshold"
                  Metrics[F]
                    .updateGauge(
                      "dag_currency_consensus_outcome_corroboration_proof_signers",
                      preferredOutcomePeers.size.toLong
                    ) >> Metrics[F]
                    .updateGauge("dag_currency_consensus_outcome_corroboration_responsive_ready", peers.size.toLong) >> Metrics[F]
                    .updateGauge("dag_currency_consensus_outcome_corroboration_responses_served", served.toLong) >> Metrics[F]
                    .updateGauge("dag_currency_consensus_outcome_corroboration_valid_responses", analysis.validResponses.toLong) >> Metrics[
                    F
                  ]
                    .updateGauge("dag_currency_consensus_outcome_corroboration_threshold", analysis.threshold.toLong) >> Metrics[F]
                    .updateGauge("dag_currency_consensus_outcome_corroboration_max_matching", analysis.maxMatching.toLong) >> Metrics[F]
                    .updateGauge(
                      "dag_currency_consensus_outcome_corroboration_distinct_valid_values",
                      analysis.distinctValidValues.toLong
                    ) >> Metrics[F]
                    .incrementCounter(
                      "dag_currency_consensus_outcome_corroboration_total",
                      Seq(Metrics.unsafeLabelName("outcome") -> outcome)
                    )
                    .as(analysis.selected)
                }
            }

          def wasSuccessful(maybeOutcome: Option[Outcome]): F[Boolean] =
            maybeOutcome.isDefined.pure[F]

          def onFailure(maybeOutcome: Option[Outcome], retryDetails: RetryDetails): F[Unit] =
            maybeOutcome.map { outcome =>
              val sameArtifact = _artifact.get(outcome) === lastArtifact
              val sameContext = _context.get(outcome) === lastContext
              logger.info(
                s"Observed outcome {key=${key.show}, outcomeKey=${_key
                    .get(outcome)}, sameArtifact=${sameArtifact.show}, sameContext=${sameContext.show}, attempt=${retryDetails.retriesSoFar}}"
              )
            }.getOrElse(
              logger.info(
                s"Corroborated Currency outcome not observed {key=${key.show}, proofSignerCount=${preferredOutcomePeers.size}, attempt=${retryDetails.retriesSoFar}}"
              )
            )

          def onError(err: Throwable, retryDetails: RetryDetails): F[Unit] =
            logger.error(err)(s"Error when trying to observe consensus outcome {attempt=${retryDetails.retriesSoFar}}")

          (for {
            maybeOutcome <- fetchCorroboratedOutcome
            outcome <- maybeOutcome.liftTo[F](
              new Throwable(
                s"Corroborated Currency outcome not observed, giving up {key=${key.show}, proofSignerCount=${preferredOutcomePeers.size}}"
              )
            )
            _ <- Async[F].uncancelable { _ =>
              // The exact observed outcome selects any local durable representation that may
              // survive canonical replacement. Only after that reconciliation may private
              // outcome authority atomically leave Observing and enter storage.
              beforeOutcomeInstall(outcome) >> nodeStorage
                .tryModifyState(Observing, WaitingForReady)
                .flatMap(_ =>
                  consensusStorage
                    .trySetInitialConsensusOutcome(outcome)
                    .ifM(Async[F].unit, new Throwable("Error initializing consensus storage").raiseError[F, Unit])
                )
            }
            _ <- internalFacilitateWith(none)
            stateCreated <- consensusStorage.getState(key.next).map(_.isDefined)
            outcomeAdvanced <- consensusStorage.getLastConsensusOutcome.map(_.exists(outcome => _key.get(outcome) =!= key))
            _ <- Async[F].raiseUnless(stateCreated || outcomeAdvanced)(
              new IllegalStateException(
                s"Downloaded Currency hand-off did not create the authorized next generation {key=${key.next.show}}"
              )
            )
            _ <- afterOutcomeInstall
          } yield ()).handleErrorWith { error =>
            consensusStorage.getState(key.next).flatMap {
              case Some(_) =>
                logger.warn(error)(s"Currency outcome was installed and its first round exists; retaining generation {key=${key.show}}") >>
                  afterOutcomeInstall
              case None =>
                logger.warn(error)(s"Exact Currency consensus outcome was not observed; re-anchoring download {key=${key.show}}") >>
                  consensusStorage.getLastConsensusOutcome.flatMap {
                    case Some(current) if _key.get(current) =!= key =>
                      logger.warn(
                        s"Skipping Currency re-anchor cleanup because a newer generation owns consensus " +
                          s"{requestedKey=${key.show}, ownerKey=${_key.get(current).show}}"
                      )
                    case _ =>
                      // No outcome is the normal path when this validator registered but was
                      // not selected in the bounded candidate set. Clear the observation and
                      // re-download so it can register at a later key.
                      consensusStorage.resetInitialConsensusOutcome(key).void >>
                        consensusStorage.clearObservationKey >>
                        nodeStorage
                          .tryModifyStateGetResult(Set[NodeState](Observing, WaitingForReady), WaitingForDownload)
                          .flatMap {
                            case NodeStateTransition.Success => Async[F].unit
                            case NodeStateTransition.Failure =>
                              nodeStorage.getNodeState.flatMap { state =>
                                logger.warn(s"Unable to re-anchor Currency download from node state ${state.show}")
                              }
                          }
                  }
            }
          }
        }.void

      def facilitateOnEvent: F[Unit] =
        S.supervise {
          internalFacilitateWith(EventTrigger.some)
            .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
        }.void

      def startFacilitatingAfterRollback(lastKey: Key, initialOutcome: Outcome): F[Unit] =
        consensusStorage
          .trySetInitialConsensusOutcome(initialOutcome)
          .ifM(
            consensusStorage.trySetObservationKey(lastKey) >>
              scheduleFacility,
            new Throwable("Error initializing consensus storage").raiseError[F, Unit]
          )

      def abortObservation: F[Unit] = consensusStorage.clearObservationKey

      private def scheduleFacility: F[Unit] =
        Clock[F].monotonic.map(_ + config.timeTriggerInterval).flatMap { nextTimeValue =>
          consensusStorage.setTimeTrigger(nextTimeValue) >>
            S.supervise {
              val condTriggerWithTime = for {
                maybeTimeTrigger <- consensusStorage.getTimeTrigger
                currentTime <- Clock[F].monotonic
                _ <- Applicative[F]
                  .whenA(maybeTimeTrigger.exists(currentTime >= _))(internalFacilitateWith(TimeTrigger.some))
              } yield ()

              Temporal[F].sleep(config.timeTriggerInterval) >> condTriggerWithTime
                .handleErrorWith(logger.error(_)(s"Error triggering consensus with time trigger"))
            }.void
        }

      def withdrawFromConsensus: F[Unit] =
        for {
          maybeLastOutcome <- consensusStorage.clearAndGetLastConsensusOutcome
          _ <- maybeLastOutcome.traverse { lastOutcome =>
            consensusStateRemover.withdrawFromConsensus(_key.get(lastOutcome).next)
          }
          _ <- consensusStorage.clearObservationKey
        } yield ()

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact, Kind]): F[Unit] =
        S.supervise {
          internalCheckForStateUpdate(key, resources)
            .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
        }.void

      private def internalFacilitateWith(
        trigger: Option[ConsensusTrigger]
      ): F[Unit] =
        consensusStorage.getLastConsensusOutcome.flatMap { maybeLastOutcome =>
          maybeLastOutcome.traverse { lastOutcome =>
            val nextKey = _key.get(lastOutcome).next

            consensusStorage
              .getResources(nextKey)
              .flatMap { resources =>
                logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                  consensusStateCreator.tryFacilitateConsensus(nextKey, lastOutcome, trigger, resources).flatMap {
                    case Some(state) =>
                      stallDetection(nextKey, state) >>
                        internalCheckForStateUpdate(nextKey, resources)
                    case None => internalCheckForStateUpdate(nextKey, resources)
                  }
              }
          }.void
        }

      private def internalCheckForStateUpdate(
        key: Key,
        resources: ConsensusResources[Artifact, Kind]
      ): F[Unit] =
        consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
          case Some((oldState, newState)) =>
            consensusStateAdvancer.getConsensusOutcome(newState) match {
              case Some((previousKey, newOutcome)) => commitOutcome(previousKey, newOutcome, newState)
              case None =>
                stallDetection(key, newState).whenA(oldState.status =!= newState.status) >>
                  internalCheckForStateUpdate(key, resources)
            }
          case None =>
            consensusStorage
              .getState(key)
              .flatMap(_.traverse_ { currentState =>
                consensusStateAdvancer.getConsensusOutcome(currentState).traverse_ {
                  case (previousKey, outcome) => commitOutcome(previousKey, outcome, currentState)
                }
              })
        }

      private def commitOutcome(
        previousKey: Previous[Key],
        newOutcome: Outcome,
        finishedState: ConsensusState[Key, Status, Outcome, Kind]
      ): F[Unit] =
        Clock[F].monotonic.flatMap { finishedAt =>
          Metrics[F].recordTime("dag_consensus_duration", finishedAt - finishedState.createdAt)
        } >>
          consensusStorage
            .tryUpdateLastConsensusOutcomeWithCleanup(previousKey, newOutcome)
            .flatMap {
              case false => logger.info("Skip triggering another consensus")
              case true if isAuthorizedForNextRound(newOutcome) =>
                afterConsensusFinish(_trigger.get(newOutcome)) >>
                  nodeStorage.tryModifyStateGetResult(WaitingForReady, Ready).void
              case true =>
                // A live incumbent may be ACK-removed after committing the same Finished value as
                // the retained committee. Do not leave it Ready with a stale observation key and
                // no facilitation path. Only after the outcome and retained finalization effect are
                // durable do we discard private committee authority and re-enter ordinary download
                // -> observe -> register admission.
                Metrics[F].incrementCounter("dag_currency_consensus_self_excluded_total") >>
                  logger.warn(
                    s"Local node was excluded from committed Currency outcome; re-entering download at key=${_key.get(newOutcome).show}"
                  ) >>
                  consensusStorage.clearTimeTrigger >>
                  consensusStorage.clearObservationKey >>
                  consensusStorage.clearAndGetLastConsensusOutcome.void >>
                  nodeStorage
                    .tryModifyStateGetResult(Set[NodeState](Ready, WaitingForReady), WaitingForDownload)
                    .flatMap {
                      case NodeStateTransition.Success => Async[F].unit
                      case NodeStateTransition.Failure =>
                        nodeStorage.getNodeState
                          .flatMap(state => logger.warn(s"Unable to re-anchor excluded Currency member from ${state.show}"))
                    }
            }

      private def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
        majorityTrigger match {
          case EventTrigger => afterEventTrigger
          case TimeTrigger  => afterTimeTrigger
        }

      private def afterEventTrigger: F[Unit] =
        for {
          maybeTimeTrigger <- consensusStorage.getTimeTrigger
          currentTime <- Clock[F].monotonic
          _ <-
            if (maybeTimeTrigger.exists(currentTime >= _))
              internalFacilitateWith(TimeTrigger.some)
            else if (maybeTimeTrigger.isEmpty)
              scheduleFacility
            else
              Applicative[F].unit
        } yield ()

      private def afterTimeTrigger: F[Unit] =
        scheduleFacility

      private def stallDetection(key: Key, state: ConsensusState[Key, Status, Outcome, Kind]): F[Unit] =
        S.supervise {
          Temporal[F].sleep(config.declarationTimeout) >>
            consensusStateUpdater.tryLockConsensus(key, state).flatMap { maybeResult =>
              maybeResult.traverse {
                case (_, lockedState) =>
                  Temporal[F].sleep(config.lockDuration) >>
                    consensusOps.maybeCollectingKind(lockedState.status).traverse { ackKind =>
                      consensusStorage.getResources(key).flatMap { resources =>
                        consensusStateUpdater.trySpreadAck(key, ackKind, resources)
                      }
                    }
              }
            }
        }.void

    }

    S.supervise(
      nodeStorage.nodeStates
        .filter(_ === NodeState.Leaving)
        .evalTap { _ =>
          manager.withdrawFromConsensus
        }
        .compile
        .drain
    ) >>
      S.supervise {
        val observingChanges = clusterStorage.peerChanges.mapFilter {
          case Both(_, peer) if peer.state === NodeState.Observing =>
            peer.some
          case Right(peer) if peer.state === NodeState.Observing =>
            peer.some
          case _ =>
            none[Peer]
        }

        // Peer-change delivery is edge-triggered. A transient HTTP failure during that one edge
        // must not strand an otherwise healthy validator in Observing forever, so periodically
        // re-poll only the still-Observing responsive population. registerPeer is idempotent.
        val observingPolls = Stream
          .awakeEvery[F](15.seconds)
          .evalMap(_ => clusterStorage.getResponsivePeers)
          .flatMap(peers => Stream.emits(peers.toList.filter(_.state === NodeState.Observing)))

        observingChanges
          .merge(observingPolls)
          .filter(_.isResponsive)
          .parEvalMapUnbounded { peer =>
            collectRegistration(peer)
              .retryingOnAllErrors(
                collectRegistrationRetryPolicy,
                (err, retryDetails) =>
                  logger
                    .error(err)(s"Error collecting consensus registration {peerId=${peer.id.show}, attempt=${retryDetails.retriesSoFar}}")
              )
              .handleErrorWith(err => logger.error(err)(s"Unable to collect registration from peer ${peer.show}"))
          }
          .compile
          .drain
      } >>
      S.supervise {
        Stream
          .awakeEvery[F](config.timeTriggerInterval)
          .evalMap(_ =>
            reanchorWhenAuthorityMajorityIsAhead.handleErrorWith(logger.warn(_)("Unable to evaluate Currency peer-ahead re-anchor"))
          )
          .compile
          .drain
      }.as(manager)
  }
}
