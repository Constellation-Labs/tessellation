package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptySet, OptionT, StateT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.l0.snapshot.synchronous.ConsensusStateUpdater._
import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration._
import io.constellationnetwork.currency.l0.snapshot.synchronous.message._
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

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

  private val hashOrdering: Ordering[Hash] = cats.Order[Hash].toOrdering

  private[snapshot] final case class CandidateSelection(candidates: Candidates, cursor: Option[PeerId])

  private[snapshot] def boundedFacilityEventHashes(hashes: Iterable[Hash]): SortedSet[Hash] =
    SortedSet.from(hashes)(hashOrdering).take(EventMempool.DefaultSnapshotLimit)

  /** Events not accepted against this parent remain eligible for a later round. `rejected` means rejected by this derivation, not
    * permanently invalid: Currency/GL0 state can advance and make the same token-lock or spend event valid on the next parent.
    */
  private[snapshot] def retainedAfterProposal(
    awaiting: Set[CurrencySnapshotEvent],
    rejected: Set[CurrencySnapshotEvent]
  ): Set[CurrencySnapshotEvent] = awaiting ++ rejected

  /** Newly admitted validators begin their first generation without a local timer and therefore advertise `None`. If every retained
    * Facility is `None` (for example after ACK-removing the bootstrap lead), use the repository's pinned empty-majority default. This is
    * derived only after the complete retained Facility set exists.
    */
  private[snapshot] def selectFacilityTrigger(
    triggers: Iterable[Option[ConsensusTrigger]]
  ): ConsensusTrigger =
    pickMajority(triggers.flatten.toList).getOrElse(EventTrigger)

  /** Bounds flat-committee growth so the incumbent committee can still form the strict-majority ACK needed to remove a newly admitted peer
    * that disappears before its first declaration. The controlled singleton bootstrap is the only exception: it admits at most two
    * validators so the normal three-member metagraph shape can form in one completed round. The legacy flat-consensus
    * `max-facilitator-count` remains the operator cap: it limits new admissions but never sheds an already-authorized incumbent.
    */
  private[snapshot] def selectCandidates(
    facilitators: Set[PeerId],
    registered: Set[PeerId],
    previousCursor: Option[PeerId],
    maxFacilitatorCount: Int
  ): CandidateSelection = {
    val safetyMaximum = if (facilitators.size === 1) 2 else math.max(0, facilitators.size - 1)
    val configuredHeadroom = math.max(0, maxFacilitatorCount - facilitators.size)
    val maximum = math.min(safetyMaximum, configuredHeadroom)
    val ordered = registered.diff(facilitators).toList.sorted
    val rotated = previousCursor.fold(ordered) { cursor =>
      val (atOrBefore, after) = ordered.span(_ <= cursor)
      after ++ atOrBefore
    }
    val selected = rotated.take(maximum)

    CandidateSelection(Candidates(selected.toSet), selected.lastOption.orElse(previousCursor))
  }

  /** Projects the exact next-round incumbents and then bounds eligible candidates against that set.
    *
    * Eligibility is evaluated from the just-finalized signed artifact/context. Doing this only in the next StateCreator would let the
    * candidate cap use a larger, stale incumbent denominator.
    */
  private[snapshot] def projectNextRoundMembership[F[_]: cats.Monad](
    incumbents: List[PeerId],
    candidates: Set[PeerId],
    previousCursor: Option[PeerId],
    maxFacilitatorCount: Int,
    seedlistAllows: PeerId => Boolean
  )(
    parentAllows: PeerId => F[Boolean]
  ): F[Option[(List[PeerId], Candidates, Option[PeerId])]] =
    for {
      eligibleIncumbents <- incumbents.distinct.sorted.filter(seedlistAllows).filterA(parentAllows)
      eligibleCandidates <- candidates.diff(incumbents.toSet).toList.sorted.filter(seedlistAllows).filterA(parentAllows)
      bounded = selectCandidates(eligibleIncumbents.toSet, eligibleCandidates.toSet, previousCursor, maxFacilitatorCount)
    } yield Option.when(eligibleIncumbents.nonEmpty)((eligibleIncumbents, bounded.candidates, bounded.cursor))

  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    config: ConsensusConfig,
    selfId: PeerId,
    keyPair: KeyPair,
    consensusStorage: CurrencyConsensusStorage[F],
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    gossip: Gossip[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    restartService: RestartService[F, _],
    nodeStorage: NodeStorage[F],
    leavingDelay: FiniteDuration,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    seedlist: Option[Set[SeedlistEntry]],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey]
  ): CurrencySnapshotConsensusStateAdvancer[F] =
    new CurrencySnapshotConsensusStateAdvancer[F] {
      val logger = Slf4jLogger.getLogger[F]

      val facilitatorsObservationName = "facilitators"

      private def parentArtifactHash(state: CurrencySnapshotConsensusState): F[Hash] =
        HasherSelector[F].withCurrent { implicit hasher =>
          state.lastOutcome.finished.signedMajorityArtifact.hash
        }

      private def declarationDomain(
        state: CurrencySnapshotConsensusState,
        facilitatorsHash: Hash
      ): F[AttemptDomain] =
        parentArtifactHash(state).map(AttemptDomain(facilitatorsHash, _, state.lastOutcome.finished.binaryArtifactHash))

      /** Fetch every event named by the complete Facility union. Proposal construction remains blocked if even one hash is unavailable. */
      private def resolveFacilityEvents(
        facilities: SortedMap[PeerId, Facility]
      ): F[Option[(SortedSet[Hash], Map[Hash, CurrencySnapshotEvent])]] = {
        val union = boundedFacilityEventHashes(facilities.valuesIterator.flatMap(_.eventHashes).toList)
        for {
          available <- eventMempool.getMultiple(union)
          _ <- logger
            .warn(s"Waiting for ${union.size - available.size} Facility-declared Currency events")
            .whenA(available.size < union.size)
        } yield Option.when(available.size === union.size)(union -> available.view.mapValues(_.signed.value).toMap)
      }

      private def removeMatchingEvents(events: Set[CurrencySnapshotEvent], reason: String): F[Unit] =
        if (events.isEmpty) Applicative[F].unit
        else
          for {
            hashes <- eventMempool.getEventHashes
            stored <- eventMempool.getMultiple(hashes)
            matching = stored.collect { case (hash, event) if events.contains(event.signed.value) => hash }.toSet
            _ <- eventMempool.remove(matching).whenA(matching.nonEmpty)
            _ <- logger
              .info(s"Removed ${matching.size} Currency mempool events after $reason")
              .whenA(matching.nonEmpty)
          } yield ()

      /** Reconstruct only the per-round events committed by the winning artifact. This is independent of which local proposal won, so every
        * facilitator clears the same semantic events even when randomized proposal delivery selected another peer's otherwise valid
        * artifact.
        */
      private def committedEvents(artifact: CurrencySnapshotArtifact): F[Set[CurrencySnapshotEvent]] = {
        val blockEvents = artifact.blocks.unsorted.toList.map(_.block).map(BlockEvent(_))
        val allowSpendEvents = artifact.allowSpendBlocks.toList.flatMap(_.toList.map(AllowSpendBlockEvent(_)))
        val tokenLockEvents = artifact.tokenLockBlocks.toList.flatMap(_.toList.map(TokenLockBlockEvent(_)))
        val messageEvents = artifact.messages.toList.flatMap(_.toList.map(CurrencyMessageEvent(_)))
        val globalSnapshotSyncEvents = artifact.globalSnapshotSyncs.toList.flatMap(_.toList.map(GlobalSnapshotSyncEvent(_)))
        val dataApplicationEvents = maybeDataApplication.flatTraverse { service =>
          artifact.dataApplication.map(_.blocks).traverse(_.traverse(service.deserializeBlock))
        }.map(_.map(_.flatMap(_.toOption).map(DataApplicationBlockEvent(_))).getOrElse(List.empty))

        dataApplicationEvents.map { dataEvents =>
          (blockEvents ++ allowSpendEvents ++ tokenLockEvents ++ messageEvents ++ globalSnapshotSyncEvents ++ dataEvents).toSet
        }
      }

      private def clearCommittedEvents(artifact: CurrencySnapshotArtifact): F[Unit] =
        committedEvents(artifact).flatMap(removeMatchingEvents(_, s"finalizing Currency ordinal=${artifact.ordinal.show}"))

      def getConsensusOutcome(
        state: CurrencySnapshotConsensusState
      ): Option[(Previous[CurrencySnapshotKey], CurrencyConsensusOutcome)] =
        state.status match {
          case f @ Finished(_, _, _, _, _, _, _) =>
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
                  for {
                    expectedDomain <- declarationDomain(state, ownFacilitatorsHash)
                    maybeFacilities <- maybeGetAllDeclarations(state, resources, config)(_.facility.filter(_.domain === expectedDomain))
                    result <- maybeFacilities match {
                      case None => none[(CurrencySnapshotConsensusState, F[Unit])].pure[F]
                      case Some(facilities) =>
                        val candidateSelection = selectCandidates(
                          state.facilitators.value.toSet,
                          facilities.valuesIterator.flatMap(_.candidates.value).toSet,
                          state.lastOutcome.finished.candidateCursor,
                          config.maxFacilitatorCount.fold(Int.MaxValue)(_.value)
                        )
                        val candidates = candidateSelection.candidates
                        val majorityTrigger = selectFacilityTrigger(facilities.valuesIterator.map(_.trigger).toList)

                        recoverIfForking[F](ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(
                          facilities.map { case (peerId, facility) => peerId -> facility.facilitatorsHash }
                        ) >> {
                          resolveFacilityEvents(facilities).flatMap {
                            case None => none[(CurrencySnapshotConsensusState, F[Unit])].pure[F]
                            case Some((_, hashToEvent)) =>
                              Applicative[F].whenA(majorityTrigger === TimeTrigger)(consensusStorage.clearTimeTrigger) >>
                                ownFacilitatorsHash.pure[F].flatMap { facilitatorsHash =>
                                  for {
                                    created <- consensusFns.createProposalArtifactWithDisposition(
                                      state.key,
                                      state.lastOutcome.finished.signedMajorityArtifact,
                                      state.lastOutcome.finished.context,
                                      hasher,
                                      majorityTrigger,
                                      hashToEvent.values.toSet,
                                      state.facilitators.value.toSet,
                                      getGlobalSnapshotByOrdinal
                                    )
                                    artifact = created.artifact
                                    context = created.context
                                    returnedEvents = retainedAfterProposal(created.awaitingEvents, created.rejectedEvents)
                                    returnedEventHashes = SortedSet.from(hashToEvent.iterator.collect {
                                      case (hash, event) if returnedEvents.contains(event) => hash
                                    })(hashOrdering)
                                    acceptedEventHashes = SortedSet.from(hashToEvent.keySet -- returnedEventHashes)(hashOrdering)
                                    hash <- artifact.hash
                                    parentHash <- parentArtifactHash(state)
                                    proposal = Proposal(
                                      hash,
                                      AttemptDomain(facilitatorsHash, parentHash, state.lastOutcome.finished.binaryArtifactHash)
                                    )
                                    // Preserve both awaiting and rejected events for a later parent. Currency acceptance can reject an
                                    // event against the current Currency/GL0 state even though it becomes valid after a subsequent
                                    // snapshot (token-lock and spend workflows rely on this). Move returned work to the FIFO tail so one
                                    // permanently invalid entry cannot consume the bounded proposal head forever. Only
                                    // clearCommittedEvents removes events, after the winning artifact is persisted.
                                    effect = eventMempool.deferToBack(returnedEventHashes.toSet) >>
                                      consensusStorage.addProposal(selfId, state.key, proposal, proposal.domain.some) >>
                                      consensusStorage.addArtifact(state.key, artifact).void >>
                                      gossip.spread(ConsensusPeerDeclaration(state.key, proposal)) >>
                                      gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
                                    newState = state.copy(status =
                                      identity[CurrencySnapshotStatus](
                                        CollectingProposals(
                                          majorityTrigger,
                                          ArtifactInfo(artifact, context, hash),
                                          candidates,
                                          acceptedEventHashes,
                                          facilitatorsHash
                                        )
                                      )
                                    )
                                  } yield (newState -> effect).some
                                }
                          }
                        }
                    }
                  } yield result

                case CollectingProposals(majorityTrigger, proposalInfo, candidates, ownAcceptedEventHashes, ownFacilitatorsHash) =>
                  for {
                    expectedDomain <- declarationDomain(state, ownFacilitatorsHash)
                    maybeAllProposals <- maybeGetAllDeclarations(state, resources, config)(_.proposal.filter(_.domain === expectedDomain))
                    result <- maybeAllProposals.traverseTap(d =>
                      recoverIfForking(ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(d.map {
                        case (peerId, proposal) => (peerId, proposal.facilitatorsHash)
                      })
                    ) >>
                      maybeAllProposals
                        .map(allProposals => allProposals.values.toList.map(_.hash))
                        .flatTraverse { allProposalHashes =>
                          pickValidatedMajorityArtifact(
                            proposalInfo,
                            state.lastOutcome.finished.signedMajorityArtifact,
                            state.lastOutcome.finished.context,
                            majorityTrigger,
                            resources,
                            allProposalHashes,
                            state.facilitators.value.toSet,
                            consensusFns,
                            getGlobalSnapshotByOrdinal
                          ).flatMap { maybeMajorityArtifactInfo =>
                            ownFacilitatorsHash.pure[F].flatMap { facilitatorsHash =>
                              maybeMajorityArtifactInfo.traverse { majorityArtifactInfo =>
                                val acceptedEventHashes =
                                  if (majorityArtifactInfo.hash === proposalInfo.hash) ownAcceptedEventHashes else SortedSet.empty[Hash]
                                val newState =
                                  state.copy(status =
                                    identity[CurrencySnapshotStatus](
                                      CollectingSignatures(
                                        majorityArtifactInfo,
                                        majorityTrigger,
                                        candidates,
                                        acceptedEventHashes,
                                        facilitatorsHash
                                      )
                                    )
                                  )
                                for {
                                  parentHash <- parentArtifactHash(state)
                                  signature <- Signature.fromHash(keyPair.getPrivate, majorityArtifactInfo.hash)
                                  declaration = MajoritySignature(
                                    signature,
                                    majorityArtifactInfo.hash,
                                    AttemptDomain(facilitatorsHash, parentHash, state.lastOutcome.finished.binaryArtifactHash)
                                  )
                                  effect = consensusStorage.addSignature(selfId, state.key, declaration, declaration.domain.some) >>
                                    gossip.spread(ConsensusPeerDeclaration(state.key, declaration)) >>
                                    Metrics[F].recordDistribution(
                                      "dag_consensus_proposal_affinity",
                                      proposalAffinity(allProposalHashes, proposalInfo.hash)
                                    )
                                } yield (newState, effect)
                              }
                            }
                          }
                        }
                  } yield result

                case CollectingSignatures(
                      majorityArtifactInfo,
                      majorityTrigger,
                      candidates,
                      acceptedEventHashes,
                      ownFacilitatorsHash
                    ) =>
                  for {
                    expectedDomain <- declarationDomain(state, ownFacilitatorsHash)
                    maybeAllSignatures <- maybeGetAllDeclarations(state, resources, config)(_.signature.filter(_.domain === expectedDomain))
                    result <- maybeAllSignatures
                      .traverseTap(signatures =>
                        recoverIfForking(ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(
                          signatures.map {
                            case (peerId, majoritySignature) => (peerId, majoritySignature.facilitatorsHash)
                          }
                        )
                      )
                      .flatMap { maybeSignatures =>
                        maybeSignatures
                          .map(_.filter { case (_, signature) => signature.artifactHash === majorityArtifactInfo.hash })
                          .map(_.map { case (id, signature) => SignatureProof(PeerId._Id.get(id), signature.signature) }.toList)
                          .traverse { allSignatures =>
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
                              .map(valid => Option.when(valid.size === state.facilitators.value.size)(valid))
                          }
                          .flatMap { maybeOnlyValidSignatures =>
                            ownFacilitatorsHash.pure[F].flatMap { facilitatorsHash =>
                              maybeOnlyValidSignatures.flatten.flatMap(sigs => NonEmptySet.fromSet(sigs.toSortedSet)).traverse {
                                validSignaturesNes =>
                                  val signedArtifact = Signed(majorityArtifactInfo.artifact, validSignaturesNes)
                                  val maybeStakingAddress = fetchStakingAddress(state.lastOutcome.finished.context.snapshotInfo)

                                  for {
                                    binary <- stateChannelSnapshotService.createSynchronousBinaryValue(
                                      signedArtifact,
                                      state.lastOutcome.finished.binaryArtifactHash,
                                      maybeStakingAddress
                                    )
                                    binaryHash <- binary.hash
                                    signature <- Signature.fromHash(keyPair.getPrivate, binaryHash)
                                    parentHash <- parentArtifactHash(state)
                                    declaration = BinarySignature(
                                      signature,
                                      binaryHash,
                                      AttemptDomain(facilitatorsHash, parentHash, state.lastOutcome.finished.binaryArtifactHash)
                                    )
                                    newState = state.copy(status =
                                      identity[CurrencySnapshotStatus](
                                        CollectingBinarySignatures(
                                          signedArtifact,
                                          majorityArtifactInfo.context,
                                          binary,
                                          majorityTrigger,
                                          candidates,
                                          acceptedEventHashes,
                                          facilitatorsHash
                                        )
                                      )
                                    )
                                    effect = consensusStorage.addBinarySignature(selfId, state.key, declaration, declaration.domain.some) >>
                                      gossip.spread(ConsensusPeerDeclaration(state.key, declaration))
                                  } yield (newState, effect)
                              }
                            }
                          }
                      }
                  } yield result

                case CollectingBinarySignatures(
                      signedMajorityArtifact,
                      context,
                      binary,
                      majorityTrigger,
                      candidates,
                      acceptedEventHashes,
                      ownFacilitatorsHash
                    ) =>
                  {
                    for {
                      expectedDomain <- OptionT.liftF(declarationDomain(state, ownFacilitatorsHash))
                      maybeAllBinarySignatures <- OptionT.liftF(
                        maybeGetAllDeclarations(state, resources, config)(_.binarySignature.filter(_.domain === expectedDomain))
                      )
                      binarySignatures <- OptionT.fromOption[F](maybeAllBinarySignatures)
                      _ <- OptionT.liftF(
                        recoverIfForking(ownFacilitatorsHash, facilitatorsObservationName, restartService, nodeStorage, leavingDelay)(
                          binarySignatures.map { case (peerId, binarySignature) => (peerId, binarySignature.facilitatorsHash) }
                        )
                      )
                      binaryHash <- OptionT.liftF(binary.hash)
                      matchingBinarySignatures = binarySignatures.filter { case (_, declaration) => declaration.binaryHash === binaryHash }
                      allSignatures = matchingBinarySignatures.map {
                        case (id, bs) => SignatureProof(PeerId._Id.get(id), bs.signature)
                      }.toList
                      validSignatures <- OptionT.liftF(allSignatures.filterA(verifySignatureProof(binaryHash, _)))
                      _ <- OptionT.liftF {
                        logger
                          .warn(
                            s"Removed ${(allSignatures.size - validSignatures.size).show} invalid binary signatures during consensus for key ${state.key.show}, " +
                              s"${validSignatures.size.show} valid signatures left"
                          )
                          .whenA(allSignatures.size =!= validSignatures.size)
                      }
                      _ <- OptionT.fromOption[F](Option.when(validSignatures.size === state.facilitators.value.size)(()))
                      validSignaturesNes <- OptionT.fromOption(NonEmptySet.fromSet(validSignatures.toSortedSet))
                      projectedMembership <- OptionT.liftF(
                        projectNextRoundMembership(
                          state.facilitators.value,
                          candidates.value,
                          state.lastOutcome.finished.candidateCursor,
                          config.maxFacilitatorCount.fold(Int.MaxValue)(_.value),
                          peerId => seedlist.forall(_.map(_.peerId).contains(peerId))
                        )(consensusFns.facilitatorFilter(signedMajorityArtifact, context, _))
                      )
                      _ <- OptionT.liftF(
                        logger
                          .error(
                            s"Refusing to finish Currency ordinal=${state.key.show}: the signed child leaves no eligible incumbent"
                          )
                          .whenA(projectedMembership.isEmpty)
                      )
                      (nextIncumbents, finalCandidates, candidateCursor) <- OptionT.fromOption[F](projectedMembership)
                      facilitatorsHash <- OptionT.liftF(nextIncumbents.hash)
                      finalSignedBinary = Signed(binary, validSignaturesNes)
                      hashedBinary <- OptionT.liftF(finalSignedBinary.toHashed)
                      parentArtifact = state.lastOutcome.finished.signedMajorityArtifact
                      parentGlobalSnapshotOrdinal = parentArtifact.value.globalSyncView
                        .map(_.ordinal)
                        .getOrElse(SnapshotOrdinal.MinValue)
                      effect = stateChannelSnapshotService.prepareBinaryPublication(signedMajorityArtifact, hashedBinary) >>
                        stateChannelSnapshotService
                          .persist(
                            signedMajorityArtifact,
                            context,
                            parentArtifact.value.dataApplication,
                            parentGlobalSnapshotOrdinal
                          )
                          .flatMap { persisted =>
                            if (persisted)
                              stateChannelSnapshotService.commitBinaryPublication(
                                hashedBinary.hash,
                                signedMajorityArtifact,
                                context.snapshotInfo
                              ) >>
                                maybeDataApplication.traverse_ { da =>
                                  signedMajorityArtifact.toHashed >>= da.onSnapshotConsensusResult
                                }.handleErrorWith(logger.error(_)("Unhandled exception during onSnapshotConsensusResult")) >>
                                clearCommittedEvents(signedMajorityArtifact.value) >>
                                stateChannelSnapshotService.enqueueBinary(hashedBinary, state.key)
                            else
                              // The retained effect starts above at prepareBinaryPublication, so
                              // its next attempt creates a fresh prepared receipt before retrying
                              // the exact install. Move the node into ordinary download repair but
                              // do not complete the effect: doing so would let Finished advance
                              // without the artifact/context and binary being durable.
                              stateChannelSnapshotService.abortPreparedBinaryPublication(hashedBinary.hash) >>
                                Metrics[F].incrementCounter("dag_currency_consensus_persistence_reanchor_total").attempt.void >>
                                nodeStorage
                                  .tryModifyStateGetResult(
                                    Set[NodeState](NodeState.Ready, NodeState.WaitingForReady),
                                    NodeState.WaitingForDownload
                                  )
                                  .void >>
                                new IllegalStateException(
                                  s"Currency consensus artifact persistence rejected ordinal=${state.key.show}; " +
                                    "binary was not published and local download reconciliation is required"
                                ).raiseError[F, Unit]
                          }

                      newState = state.copy(
                        facilitators = Facilitators(nextIncumbents),
                        status = identity[CurrencySnapshotStatus](
                          Finished(
                            signedMajorityArtifact,
                            hashedBinary.hash,
                            context,
                            majorityTrigger,
                            finalCandidates,
                            facilitatorsHash,
                            candidateCursor
                          )
                        )
                      )
                    } yield (newState, effect)
                  }.value

                case Finished(_, _, _, _, _, _, _) =>
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
