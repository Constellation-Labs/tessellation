package org.tessellation.currency.l0.snapshot

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptySet, OptionT, StateT}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.currency.dataApplication.BaseDataApplicationL0Service
import org.tessellation.currency.l0.snapshot.schema._
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency.CurrencySnapshotContext
import org.tessellation.ext.collection.FoldableOps.pickMajority
import org.tessellation.ext.crypto._
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.consensus.ConsensusStateUpdater._
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.consensus.declaration._
import org.tessellation.node.shared.infrastructure.consensus.message._
import org.tessellation.node.shared.infrastructure.consensus.trigger.TimeTrigger
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import org.tessellation.node.shared.snapshot.currency._
import org.tessellation.schema.currencyMessage.fetchStakingAddress
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, _}
import org.tessellation.security.{HasherSelector, SecurityProvider}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ] {}

object CurrencySnapshotConsensusStateAdvancer {

  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    keyPair: KeyPair,
    consensusStorage: CurrencyConsensusStorage[F],
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    gossip: Gossip[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]]
  ): CurrencySnapshotConsensusStateAdvancer[F] =
    new CurrencySnapshotConsensusStateAdvancer[F] {
      val logger = Slf4jLogger.getLogger[F]

      val facilitatorsObservationName = "facilitators"

      def getConsensusOutcome(
        state: CurrencySnapshotConsensusState
      ): Option[(Previous[CurrencySnapshotKey], CurrencyConsensusOutcome)] =
        state.status match {
          case f @ Finished(_, _, _, _, _, _) =>
            val outcome = CurrencyConsensusOutcome(state.key, state.facilitators, state.removedFacilitators, state.withdrawnFacilitators, f)

            (Previous(state.lastOutcome.key), outcome).some
          case _ => None
        }

      def advanceStatus(
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): StateT[F, CurrencySnapshotConsensusState, F[Unit]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          StateT[F, CurrencySnapshotConsensusState, F[Unit]] { state =>
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
                          state.facilitators.value.hash.flatMap { facilitatorsHash =>
                            for {
                              peerEvents <- consensusStorage.pullEvents(bound)
                              events = peerEvents.toList.flatMap(_._2).map(_._2).toSet
                              (artifact, context, returnedEvents) <- consensusFns
                                .createProposalArtifact(
                                  state.key,
                                  state.lastOutcome.finished.signedMajorityArtifact,
                                  state.lastOutcome.finished.context,
                                  hasher,
                                  majorityTrigger,
                                  events,
                                  state.facilitators.value.toSet
                                )
                              returnedPeerEvents = peerEvents.map {
                                case (peerId, events) =>
                                  (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
                              }.filter { case (_, events) => events.nonEmpty }
                              _ <- consensusStorage.addEvents(returnedPeerEvents)
                              hash <- artifact.hash
                              effect = gossip.spread(ConsensusPeerDeclaration(state.key, Proposal(hash, facilitatorsHash))) *>
                                gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
                              newState =
                                state.copy(status =
                                  identity[CurrencySnapshotStatus](
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
                case CollectingProposals(majorityTrigger, proposalInfo, candidates, ownFacilitatorsHash) =>
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
                              val newState =
                                state.copy(status =
                                  identity[CurrencySnapshotStatus](
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
                case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, ownFacilitatorsHash) =>
                  val maybeAllSignatures =
                    maybeGetAllDeclarations(state, resources)(_.signature)

                  val maybeGlobalSnapshotOrdinal =
                    maybeGetAllDeclarations(state, resources)(_.facility)
                      .map(_.map { case (_, f) => f.lastGlobalSnapshotOrdinal })
                      .map(_.toList)
                      .flatMap(pickMajority(_))

                  maybeGlobalSnapshotOrdinal.flatTraverse { globalSnapshotOrdinal =>
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
                          state.facilitators.value.hash.flatMap { facilitatorsHash =>
                            maybeOnlyValidSignatures.flatMap(sigs => NonEmptySet.fromSet(sigs.toSortedSet)).traverse { validSignaturesNes =>
                              val signedArtifact = Signed(majorityArtifactInfo.artifact, validSignaturesNes)
                              val maybeStakingAddress = fetchStakingAddress(state.lastOutcome.finished.context.snapshotInfo)

                              stateChannelSnapshotService
                                .createBinary(
                                  signedArtifact,
                                  state.lastOutcome.finished.binaryArtifactHash,
                                  globalSnapshotOrdinal.some,
                                  maybeStakingAddress
                                )
                                .map { signedBinary =>
                                  val newState = state.copy(status =
                                    identity[CurrencySnapshotStatus](
                                      CollectingBinarySignatures(
                                        signedArtifact,
                                        majorityArtifactInfo.context,
                                        signedBinary.value,
                                        majorityTrigger,
                                        candidates,
                                        facilitatorsHash
                                      )
                                    )
                                  )
                                  val effect = gossip.spread(
                                    ConsensusPeerDeclaration(
                                      state.key,
                                      BinarySignature(signedBinary.proofs.head.signature, facilitatorsHash)
                                    )
                                  )

                                  (newState, effect)
                                }
                            }
                          }
                        }
                      }
                  }
                case CollectingBinarySignatures(
                      signedMajorityArtifact,
                      context,
                      binary,
                      majorityTrigger,
                      candidates,
                      ownFacilitatorsHash
                    ) =>
                  {
                    val maybeAllBinarySignatures =
                      maybeGetAllDeclarations(state, resources)(_.binarySignature)

                    for {
                      binarySignatures <- OptionT.fromOption[F](maybeAllBinarySignatures)
                      _ <- OptionT.liftF(
                        warnIfForking(ownFacilitatorsHash, facilitatorsObservationName)(
                          binarySignatures.values.toList.map(_.facilitatorsHash)
                        )
                      )
                      allSignatures = binarySignatures.map { case (id, bs) => SignatureProof(PeerId._Id.get(id), bs.signature) }.toList
                      binaryHash <- OptionT.liftF(binary.hash)
                      validSignatures <- OptionT.liftF(allSignatures.filterA(verifySignatureProof(binaryHash, _)))
                      _ <- OptionT.liftF {
                        logger
                          .warn(
                            s"Removed ${(allSignatures.size - validSignatures.size).show} invalid binary signatures during consensus for key ${state.key.show}, " +
                              s"${validSignatures.size.show} valid signatures left"
                          )
                          .whenA(allSignatures.size =!= validSignatures.size)
                      }
                      validSignaturesNes <- OptionT.fromOption(NonEmptySet.fromSet(validSignatures.toSortedSet))
                      facilitatorsHash <- OptionT.liftF(state.facilitators.value.hash)
                      finalSignedBinary = Signed(binary, validSignaturesNes)
                      hashedBinary <- OptionT.liftF(finalSignedBinary.toHashed)
                      effect = stateChannelSnapshotService.consume(signedMajorityArtifact, hashedBinary, context) >>
                        gossipForkInfo(gossip, signedMajorityArtifact) >>
                        maybeDataApplication.traverse_ { da =>
                          signedMajorityArtifact.toHashed >>= da.onSnapshotConsensusResult
                        }

                      newState = state.copy(status =
                        identity[CurrencySnapshotStatus](
                          Finished(
                            signedMajorityArtifact,
                            hashedBinary.hash,
                            context,
                            majorityTrigger,
                            candidates,
                            facilitatorsHash
                          )
                        )
                      )
                    } yield (newState, effect)
                  }.value

                case Finished(_, _, _, _, _, _) =>
                  none[(CurrencySnapshotConsensusState, F[Unit])].pure[F]
              }
            }.map { maybeStateAndEffect =>
              maybeStateAndEffect.map { case (state, effect) => (state.copy(lockStatus = LockStatus.Open), effect) }
                .getOrElse((state, Applicative[F].unit))
            }
          }
        }
    }
}
