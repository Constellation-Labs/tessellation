package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, LastGlobalSnapshotsSyncConfig}
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.TimeTrigger
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class GlobalSnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ] {}

object GlobalSnapshotConsensusStateAdvancer {
  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    config: ConsensusConfig,
    keyPair: KeyPair,
    consensusStorage: GlobalConsensusStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    gossip: Gossip[F],
    restartService: RestartService[F, _],
    nodeStorage: NodeStorage[F],
    leavingDelay: FiniteDuration,
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  ): GlobalSnapshotConsensusStateAdvancer[F] = new GlobalSnapshotConsensusStateAdvancer[F] {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("GlobalSnapshotConsensusStateAdvancer")
    val facilitatorsObservationName = "facilitators"

    def getConsensusOutcome(
      state: GlobalSnapshotConsensusState
    ): Option[(Previous[GlobalSnapshotKey], GlobalConsensusOutcome)] =
      state.status match {
        case f @ Finished(_, _, _, _, _) =>
          val outcome = GlobalConsensusOutcome(
            state.key,
            state.facilitators,
            state.removedFacilitators,
            state.withdrawnFacilitators,
            Finished(f.signedMajorityArtifact, f.context, f.majorityTrigger, f.candidates, f.facilitatorsHash)
          )

          (Previous(state.lastOutcome.key), outcome).some
        case _ => None
      }

    def advanceStatus(
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): StateT[F, GlobalSnapshotConsensusState, F[Unit]] =
      StateT[F, GlobalSnapshotConsensusState, F[Unit]] { state =>
        if (state.lockStatus === LockStatus.Closed)
          (state, Applicative[F].unit).pure[F]
        else {
          state.status match {
            case CollectingFacilities(_, ownFacilitatorsHash) =>
              for {
                maybeFacilities <- maybeGetAllDeclarations(state, resources, config)(_.facility)
                result <- maybeFacilities.traverseTap { facilities =>
                  recoverIfForking[F](ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(
                    facilities.map {
                      case (peer, facility) => (peer, facility.facilitatorsHash)
                    }
                  )
                }.flatMap {
                  _.map(_.foldMap(f => (f.upperBound, f.candidates.value, f.trigger.toList))).flatMap {
                    case (bound, candidates, triggers) => pickMajority(triggers).map((bound, candidates, _))
                  }.traverse {
                    case (bound, candidates, majorityTrigger) =>
                      Applicative[F].whenA(majorityTrigger === TimeTrigger)(consensusStorage.clearTimeTrigger) >>
                        HasherSelector[F].withCurrent { implicit hasher =>
                          state.facilitators.value.hash
                        }.flatMap { facilitatorsHash =>
                          for {
                            peerEvents <- consensusStorage.pullEvents(bound)
                            events = peerEvents.toList.flatMap(_._2).map(_._2).toSet
                            (artifact, context, returnedEvents) <- HasherSelector[F].forOrdinal(state.key) { implicit hasher =>
                              val lastArtifact = state.lastOutcome.finished.signedMajorityArtifact
                              lastArtifact.toHashed.flatMap { hashedLastArtifact =>
                                consensusFns
                                  .createProposalArtifact(
                                    state.key,
                                    hashedLastArtifact.signed,
                                    state.lastOutcome.finished.context,
                                    HasherSelector[F].getForOrdinal(lastArtifact.ordinal),
                                    majorityTrigger,
                                    events,
                                    state.facilitators.value.toSet,
                                    getGlobalSnapshotByOrdinal
                                  )
                              }
                            }
                            returnedPeerEvents = peerEvents.map {
                              case (peerId, events) =>
                                (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
                            }.filter { case (_, events) => events.nonEmpty }
                            _ <- consensusStorage.addEvents(returnedPeerEvents)
                            hash <- HasherSelector[F].forOrdinal(artifact.ordinal)(implicit hasher => artifact.hash)
                            effect = gossip.spread(ConsensusPeerDeclaration(state.key, Proposal(hash, facilitatorsHash))) *>
                              gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
                            facilitators = state.facilitators.value
                            _ = ForkDetect.exitOnCheck("CL_EXIT_ON_FOLLOWER_ADVANCER", () => facilitators)
                            newState =
                              state.copy(status =
                                identity[GlobalSnapshotStatus](
                                  CollectingProposals(
                                    majorityTrigger,
                                    ArtifactInfo(artifact, context, hash),
                                    Candidates(candidates),
                                    facilitatorsHash
                                  )
                                )
                              )
                          } yield (newState, effect)
                        }
                  }
                }
              } yield result

            case CollectingProposals(majorityTrigger, proposalInfo, candidates, ownFacilitatorsHash) =>
              HasherSelector[F].withCurrent { implicit hasher =>
                for {
                  maybeAllProposals <- maybeGetAllDeclarations(state, resources, config)(_.proposal)
                  result <- maybeAllProposals.traverseTap(d =>
                    recoverIfForking(ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(d.map {
                      case (peerId, proposal) => (peerId, proposal.facilitatorsHash)
                    })
                  ) >>
                    maybeAllProposals
                      .map(allProposals => allProposals.values.toList.map(_.hash))
                      .flatTraverse { allProposalHashes =>
                        val lastArtifact = state.lastOutcome.finished.signedMajorityArtifact

                        lastArtifact.toHashed.flatMap { hashedLastArtifact =>
                          pickValidatedMajorityArtifact(
                            proposalInfo,
                            hashedLastArtifact.signed,
                            state.lastOutcome.finished.context,
                            majorityTrigger,
                            resources,
                            allProposalHashes,
                            state.facilitators.value.toSet,
                            consensusFns,
                            getGlobalSnapshotByOrdinal
                          ).flatMap { maybeMajorityArtifactInfo =>
                            state.facilitators.value.hash.flatMap { facilitatorsHash =>
                              maybeMajorityArtifactInfo.traverse { majorityArtifactInfo =>
                                val newState =
                                  state.copy(status =
                                    identity[GlobalSnapshotStatus](
                                      CollectingSignatures(
                                        majorityArtifactInfo,
                                        majorityTrigger,
                                        candidates,
                                        facilitatorsHash
                                      )
                                    )
                                  )
                                val effect = Signature.fromHash(keyPair.getPrivate, majorityArtifactInfo.hash).flatMap { signature =>
                                  gossip.spread(ConsensusPeerDeclaration(state.key, MajoritySignature(signature, facilitatorsHash)))
                                } >> Metrics[F].recordDistribution(
                                  "dag_consensus_proposal_affinity",
                                  proposalAffinity(allProposalHashes, proposalInfo.hash)
                                )
                                (newState, effect).pure[F]
                              }
                            }
                          }
                        }
                      }
                } yield result
              }

            case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, ownFacilitatorsHash) =>
              for {
                maybeAllSignatures <- maybeGetAllDeclarations(state, resources, config)(_.signature)
                result <- maybeAllSignatures
                  .traverseTap(signatures =>
                    recoverIfForking(ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(
                      signatures.map {
                        case (peerId, majoritySignature) => (peerId, majoritySignature.facilitatorsHash)
                      }
                    )
                  )
                  .flatMap {
                    _.map(_.map { case (id, signature) => SignatureProof(PeerId._Id.get(id), signature.signature) }.toList).traverse {
                      allSignatures =>
                        allSignatures
                          .filterA(verifySignatureProof(majorityArtifactInfo.hash, _))
                          .flatTap { validSignatures =>
                            logger
                              .warn(
                                s"Removed ${(allSignatures.size - validSignatures.size).show} invalid signatures during consensus for key ${state.key.show}, " +
                                  s"${validSignatures.size.show} valid signatures left"
                              )
                              .whenA(allSignatures.size =!= validSignatures.size)
                          }
                    }.flatMap { maybeOnlyValidSignatures =>
                      HasherSelector[F].withCurrent { implicit hasher =>
                        state.facilitators.value.hash
                      }.map { facilitatorsHash =>
                        maybeOnlyValidSignatures.flatMap { validSignatures =>
                          NonEmptySet.fromSet(validSignatures.toSortedSet).map { validSignaturesNes =>
                            val signedArtifact = Signed(majorityArtifactInfo.artifact, validSignaturesNes)

                            val newState = state.copy(status =
                              identity[GlobalSnapshotStatus](
                                Finished(
                                  signedArtifact,
                                  majorityArtifactInfo.context,
                                  majorityTrigger,
                                  candidates,
                                  facilitatorsHash
                                )
                              )
                            )

                            val effect =
                              HasherSelector[F]
                                .forOrdinal(signedArtifact.ordinal) { implicit hasher =>
                                  for {
                                    hashedSnapshot <- signedArtifact.toHashed
                                    _ <- lastNGlobalSnapshotStorage.set(hashedSnapshot, majorityArtifactInfo.context)
                                    _ <- lastGlobalSnapshotStorage.set(hashedSnapshot, majorityArtifactInfo.context)
                                    result <- globalSnapshotStorage.prepend(signedArtifact, majorityArtifactInfo.context)
                                  } yield result
                                }
                                .ifM(
                                  metrics.globalSnapshot(signedArtifact),
                                  logger.error("Cannot save GlobalSnapshot into the storage") *>
                                    MonadThrow[F].raiseError[Unit](new RuntimeException("Failed to persist GlobalSnapshot"))
                                ) >>
                                HasherSelector[F].withCurrent { implicit hasher =>
                                  gossipForkInfo(gossip, signedArtifact)
                                }

                            (newState, effect)
                          }
                        }

                      }
                    }
                  }
              } yield result
            case Finished(_, _, _, _, _) =>
              none[(GlobalSnapshotConsensusState, F[Unit])]
                .pure[F]
          }
        }.map { maybeStateAndEffect =>
          maybeStateAndEffect.map { case (state, effect) => (state.copy(lockStatus = LockStatus.Open), effect) }
            .getOrElse((state, Applicative[F].unit))
        }
      }

    object metrics {

      def globalSnapshot(signedGS: Signed[GlobalIncrementalSnapshot]): F[Unit] = {
        val activeTipsCount = signedGS.tips.remainedActive.size + signedGS.blocks.size
        val deprecatedTipsCount = signedGS.tips.deprecated.size
        val transactionCount = signedGS.blocks.map(_.block.transactions.size).sum
        val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

        Metrics[F].updateGauge("dag_global_snapshot_ordinal", signedGS.ordinal.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_height", signedGS.height.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_signature_count", signedGS.proofs.size) >>
          Metrics[F]
            .updateGauge("dag_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
          Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signedGS.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", transactionCount) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scSnapshotCount)
      }
    }
  }
}
