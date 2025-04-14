package org.tessellation.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptySet, StateT}
import cats.effect.{Async, Clock}
import cats.syntax.all._
import cats.{Applicative, Parallel}

import scala.concurrent.duration._

import org.tessellation.dag.l0.infrastructure.metrics.GlobalSnapshotMetricsAnalyzer
import org.tessellation.dag.l0.infrastructure.snapshot.schema._
import org.tessellation.ext.collection.FoldableOps.pickMajority
import org.tessellation.ext.crypto._
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.infrastructure.consensus.ConsensusStateUpdater._
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.consensus.declaration._
import org.tessellation.node.shared.infrastructure.consensus.message._
import org.tessellation.node.shared.infrastructure.consensus.trigger.TimeTrigger
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature._
import org.tessellation.security.{HasherSelector, SecurityProvider}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
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
  def make[F[_]: Async: Parallel: SecurityProvider: Metrics: HasherSelector: Clock](
    keyPair: KeyPair,
    consensusStorage: GlobalConsensusStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    gossip: Gossip[F]
  ): F[GlobalSnapshotConsensusStateAdvancer[F]] =
    GlobalSnapshotMetricsAnalyzer.make[F]().map { metricsAnalyzer =>
      new GlobalSnapshotConsensusStateAdvancer[F] {
        val logger = Slf4jLogger.getLogger[F]
        val facilitatorsObservationName = "facilitators"

        private def collectMetrics(
          context: GlobalSnapshotContext,
          key: GlobalSnapshotKey,
          startTime: FiniteDuration
        ): F[Unit] =
          Clock[F].monotonic.flatMap { currentTime =>
            context match {
              case globalInfo: GlobalSnapshotInfo =>
                // Always record minimal metrics for tracking purposes
                val minimalMetrics = metricsAnalyzer.recordBasicMetrics(globalInfo, key)

                // For detailed metrics, use sampling approach based on thresholds
                val detailedMetrics = globalInfo.lastCurrencySnapshots.toList.parTraverse_ {
                  case (address, currencySnapshot) =>
                    currencySnapshot.fold(
                      _ => Async[F].unit, // Skip full snapshots
                      {
                        case (_, currencyInfo) =>
                          val balanceSize = currencyInfo.balances.size

                          // Check if detailed metrics collection is warranted
                          metricsAnalyzer.shouldCollectDetailedMetrics(key, balanceSize).flatMap { shouldCollect =>
                            if (shouldCollect) {
                              currencyInfo.balances.get(address).traverse_ { _ =>
                                metricsAnalyzer.analyzeBalanceMap(currencyInfo, address).flatMap { _ =>
                                  metricsAnalyzer.trackStateProofTiming(currencyInfo, key).flatMap { hashTime =>
                                    val totalTime = currentTime - startTime
                                    val proposalTime = totalTime - hashTime // Approximate proposal time

                                    metricsAnalyzer.logSnapshotProcessingBreakdown(
                                      key,
                                      proposalTime,
                                      hashTime,
                                      balanceSize,
                                      totalTime
                                    )
                                  }
                                }
                              }
                            } else {
                              // Skip detailed metrics collection
                              Async[F].unit
                            }
                          }
                      }
                    )
                }

                // Execute both minimal and (conditionally) detailed metrics
                minimalMetrics >> detailedMetrics
              case _ => Async[F].unit
            }
          }

        private def createNewState[Status <: GlobalSnapshotStatus](
          state: GlobalSnapshotConsensusState,
          newStatus: Status
        ): GlobalSnapshotConsensusState =
          ConsensusState(
            state.key,
            state.lastOutcome,
            state.facilitators,
            newStatus,
            state.createdAt,
            state.removedFacilitators,
            state.withdrawnFacilitators,
            state.lockStatus,
            state.spreadAckKinds
          )

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
                  val maybeFacilities = maybeGetAllDeclarations(state, resources)(_.facility).map(_.values.toList)

                  maybeFacilities.traverseTap { facilities =>
                    warnIfForking[F](ownFacilitatorsHash, facilitatorsObservationName)(facilities.map(_.facilitatorsHash))
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

                              startTime <- Clock[F].monotonic
                              proposalResult <- HasherSelector[F].forOrdinal(state.key) { implicit hasher =>
                                val lastArtifact = state.lastOutcome.finished.signedMajorityArtifact
                                consensusFns
                                  .createProposalArtifact(
                                    state.key,
                                    lastArtifact,
                                    state.lastOutcome.finished.context,
                                    HasherSelector[F].getForOrdinal(lastArtifact.ordinal),
                                    majorityTrigger,
                                    events,
                                    state.facilitators.value.toSet
                                  )
                              }
                              (artifact, context, returnedEvents) = proposalResult
                              _ <- collectMetrics(context, state.key, startTime)

                              returnedPeerEvents = peerEvents.map {
                                case (peerId, events) =>
                                  (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
                              }.filter { case (_, events) => events.nonEmpty }
                              _ <- consensusStorage.addEvents(returnedPeerEvents)
                              hash <- HasherSelector[F].forOrdinal(artifact.ordinal)(implicit hasher => artifact.hash)
                              effect = gossip.spread(ConsensusPeerDeclaration(state.key, Proposal(hash, facilitatorsHash))) *>
                                gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
                              newStatus = CollectingProposals(
                                majorityTrigger,
                                ArtifactInfo(artifact, context, hash),
                                Candidates(candidates),
                                facilitatorsHash
                              )
                              newState = createNewState(state, newStatus)

                            } yield (newState, effect)
                          }
                    }
                  }

                case CollectingProposals(majorityTrigger, proposalInfo, candidates, ownFacilitatorsHash) =>
                  HasherSelector[F].withCurrent { implicit hasher =>
                    val maybeAllProposals =
                      maybeGetAllDeclarations(state, resources)(_.proposal).map(_.values.toList)

                    maybeAllProposals.traverseTap(d =>
                      warnIfForking(ownFacilitatorsHash, facilitatorsObservationName)(d.map(_.facilitatorsHash))
                    ) >>
                      maybeAllProposals
                        .map(allProposals => allProposals.map(_.hash))
                        .flatTraverse { allProposalHashes =>
                          pickValidatedMajorityArtifact(
                            proposalInfo,
                            state.lastOutcome.finished.signedMajorityArtifact,
                            state.lastOutcome.finished.context,
                            majorityTrigger,
                            resources,
                            allProposalHashes,
                            state.facilitators.value.toSet,
                            consensusFns
                          ).flatMap { maybeMajorityArtifactInfo =>
                            state.facilitators.value.hash.flatMap { facilitatorsHash =>
                              maybeMajorityArtifactInfo.traverse { majorityArtifactInfo =>
                                val newStatus = CollectingSignatures(
                                  majorityArtifactInfo,
                                  majorityTrigger,
                                  candidates,
                                  facilitatorsHash
                                )
                                val newState = createNewState(state, newStatus)
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
                case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, ownFacilitatorsHash) =>
                  val maybeAllSignatures =
                    maybeGetAllDeclarations(state, resources)(_.signature)

                  maybeAllSignatures
                    .traverseTap(signatures =>
                      warnIfForking(ownFacilitatorsHash, facilitatorsObservationName)(signatures.values.toList.map(_.facilitatorsHash))
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

                              val newStatus = Finished(
                                signedArtifact,
                                majorityArtifactInfo.context,
                                majorityTrigger,
                                candidates,
                                facilitatorsHash
                              )

                              val newState = createNewState(state, newStatus)

                              val effect =
                                metricsAnalyzer.timeOperation("storeSignedArtifact") {
                                  HasherSelector[F]
                                    .forOrdinal(signedArtifact.ordinal) { implicit hasher =>
                                      globalSnapshotStorage.prepend(signedArtifact, majorityArtifactInfo.context)
                                    }
                                    .ifM(
                                      metrics.globalSnapshot(signedArtifact) >>
                                        majorityArtifactInfo.context.pure[F].flatMap {
                                          case globalInfo: org.tessellation.schema.GlobalSnapshotInfo =>
                                            globalInfo.lastCurrencySnapshots.toList.traverse_ {
                                              case (address, currencySnapshot) =>
                                                currencySnapshot.fold(
                                                  _ => Async[F].unit,
                                                  {
                                                    case (_, currencyInfo) =>
                                                      signedArtifact.stateChannelSnapshots.get(address).traverse_ { _ =>
                                                        currencyInfo.balances.get(address).traverse_ { _ =>
                                                          metricsAnalyzer.analyzeBalanceMap(currencyInfo, address)
                                                        }
                                                      }
                                                  }
                                                )
                                            }
                                          case _ => Async[F].unit
                                        },
                                      logger.error("Cannot save GlobalSnapshot into the storage")
                                    )
                                } >>
                                  HasherSelector[F].withCurrent { implicit hasher =>
                                    gossipForkInfo(gossip, signedArtifact)
                                  }

                              (newState, effect)
                            }
                          }
                        }
                      }
                    }
                case Finished(_, _, _, _, _) =>
                  none[(GlobalSnapshotConsensusState, F[Unit])]
                    .pure[F]
              }
            }.map { maybeStateAndEffect =>
              maybeStateAndEffect.map {
                case (newState, effect) =>
                  val finalState = newState.copy(lockStatus = LockStatus.Open)
                  (finalState, effect)
              }.getOrElse((state, Applicative[F].unit))
            }
          }

        object metrics {

          def globalSnapshot(signedGS: Signed[GlobalIncrementalSnapshot]): F[Unit] = {
            val activeTipsCount = signedGS.tips.remainedActive.size + signedGS.blocks.size
            val deprecatedTipsCount = signedGS.tips.deprecated.size
            val transactionCount = signedGS.blocks.map(_.block.transactions.size).sum
            val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

            logger.debug(
              s"Global snapshot metrics: ordinal=${signedGS.ordinal.show}, " +
                s"height=${signedGS.height.show}, " +
                s"blocks=${signedGS.blocks.size}, " +
                s"transactions=$transactionCount, " +
                s"stateChannels=${signedGS.stateChannelSnapshots.size}, " +
                s"stateChannelSnapshots=$scSnapshotCount"
            ) >>
              Metrics[F].updateGauge("dag_global_snapshot_ordinal", signedGS.ordinal.value) >>
              Metrics[F].updateGauge("dag_global_snapshot_height", signedGS.height.value) >>
              Metrics[F].updateGauge("dag_global_snapshot_signature_count", signedGS.proofs.size) >>
              Metrics[F]
                .updateGauge("dag_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
              Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
              Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signedGS.blocks.size) >>
              Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", transactionCount) >>
              Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scSnapshotCount) >>
              Metrics[F].updateGauge(
                "dag_global_snapshot_sc_count_by_address",
                scSnapshotCount,
                Seq(("address_count", signedGS.stateChannelSnapshots.size.toString))
              )
          }
        }
      }
    }
}
