package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Advances Currency L0 (Metagraph) consensus through status phases and extracts final outcomes.
  *
  * Status Flow (note: has extra BinarySignatures phase compared to Global L0):
  * {{{
  *   CollectingFacilities → CollectingProposals → CollectingSignatures
  *     → CollectingBinarySignatures → Finished
  * }}}
  *
  * @see
  *   ConsensusStateAdvancer for the generic interface
  */
abstract class CurrencySnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

object CurrencySnapshotConsensusStateAdvancer {

  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    consensusConfig: ConsensusConfig,
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
    clusterStorageInstance: ClusterStorage[F]
  ): CurrencySnapshotConsensusStateAdvancer[F] =
    new CurrencySnapshotConsensusStateAdvancer[F] {

      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)
      private val lastSnapshotHashObservationName = "last-snapshot-hash"

      protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
      protected val config: ConsensusConfig = consensusConfig

      private case class Transition(newState: CurrencySnapshotConsensusState, sideEffect: F[Unit])

      def getConsensusOutcome(
        state: CurrencySnapshotConsensusState
      ): Option[(Previous[CurrencySnapshotKey], CurrencyConsensusOutcome)] =
        state.status match {
          case f: Finished =>
            val outcome = CurrencyConsensusOutcome(
              state.key,
              state.facilitators,
              state.removedFacilitators,
              state.withdrawnFacilitators,
              state.eligibleFacilitators,
              f
            )
            (Previous(state.lastOutcome.key), outcome).some
          case _ =>
            none
        }

      def advanceStatus(
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): StateT[F, CurrencySnapshotConsensusState, F[Unit]] =
        StateT { state =>
          HasherSelector[F].withCurrent { implicit hasher =>
            if (state.lockStatus === LockStatus.Closed)
              (state, Applicative[F].unit).pure[F]
            else
              tryAdvance(state, resources).map {
                case Some(t) => (t.newState.copy(lockStatus = LockStatus.Open), t.sideEffect)
                case None    => (state, Applicative[F].unit)
              }
          }
        }

      private def tryAdvance(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        state.status match {
          case s: CollectingFacilities       => advanceFromFacilities(state, s, resources)
          case s: CollectingProposals        => advanceFromProposals(state, s, resources)
          case s: CollectingSignatures       => advanceFromSignatures(state, s, resources)
          case s: CollectingBinarySignatures => advanceFromBinarySignatures(state, s, resources)
          case _: Finished                   => none[Transition].pure[F]
        }

      // =========================================================================
      // COLLECTING FACILITIES → COLLECTING PROPOSALS
      // =========================================================================

      private def advanceFromFacilities(
        state: CurrencySnapshotConsensusState,
        status: CollectingFacilities,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
          _ <- maybeFacilities.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          result <- maybeFacilities.flatTraverse(toProposalsPhase(state, _))
        } yield result

      private def toProposalsPhase(
        state: CurrencySnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): F[Option[Transition]] = {
        val (bound, candidates, triggers) = facilities.foldMap(f => (f.upperBound, f.candidates.value, f.trigger.toList))

        val trigger = pickMajority(triggers).getOrElse(EventTrigger)
        buildProposalTransition(state, bound, candidates, trigger).map(_.some)
      }

      private def buildProposalTransition(
        state: CurrencySnapshotConsensusState,
        bound: Bound,
        candidates: Set[PeerId],
        majorityTrigger: ConsensusTrigger
      ): F[Transition] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            _ <- clearTimeTriggerIfNeeded(majorityTrigger)
            facilitatorsHash <- hashFacilitators(state)
            peerEvents <- consensusStorage.pullEvents(bound)

            (artifact, context, returnedEvents) <- createArtifact(state, majorityTrigger, extractEvents(peerEvents))

            _ <- storeReturnedEvents(peerEvents, returnedEvents)
            hash <- hashArtifact(artifact)
          } yield
            Transition(
              newState = state.copy(status =
                CollectingProposals(
                  majorityTrigger,
                  ArtifactInfo(artifact, context, hash),
                  Candidates(candidates),
                  facilitatorsHash,
                  state.lastOutcome.finished.snapshotHash
                )
              ),
              sideEffect = spreadProposal(state.key, hash, facilitatorsHash, artifact, state.lastOutcome.finished.snapshotHash)
            )
        }

      // =========================================================================
      // COLLECTING PROPOSALS → COLLECTING SIGNATURES
      // =========================================================================

      private def advanceFromProposals(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        for {
          maybeProposals <- maybeGetAllDeclarations(state, resources)(_.proposal)
          _ <- maybeProposals.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          result <- maybeProposals.flatTraverse(toSignaturesPhase(state, status, resources, _))
        } yield result

      private def toSignaturesPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        proposals: SortedMap[PeerId, Proposal]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val hashes = proposals.values.toList.map(_.hash)

        findMajorityArtifact(state, status, resources, hashes).flatMap {
          case Some(majorityInfo) => buildSignatureTransition(state, status, majorityInfo, hashes).map(_.some)
          case None               => none[Transition].pure[F]
        }
      }

      private def findMajorityArtifact(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        proposalHashes: List[Hash]
      )(implicit hasher: Hasher[F]): F[Option[ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext]]] =
        pickValidatedMajorityArtifact(
          status.proposalArtifactInfo,
          state.lastOutcome.finished.signedMajorityArtifact,
          state.lastOutcome.finished.context,
          status.majorityTrigger,
          resources,
          proposalHashes,
          state.facilitators.value.toSet,
          consensusFns,
          getGlobalSnapshotByOrdinal
        )

      private def buildSignatureTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        majorityInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
        proposalHashes: List[Hash]
      )(implicit hasher: Hasher[F]): F[Transition] =
        for {
          facilitatorsHash <- state.facilitators.value.hash
          signature <- Signature.fromHash(keyPair.getPrivate, majorityInfo.hash)
          _ <- recordProposalAffinity(proposalHashes, status.proposalArtifactInfo.hash)
        } yield
          Transition(
            newState = state.copy(status =
              CollectingSignatures(
                majorityInfo,
                status.majorityTrigger,
                status.candidates,
                facilitatorsHash,
                state.lastOutcome.finished.snapshotHash
              )
            ),
            sideEffect = spreadSignature(state.key, signature, facilitatorsHash, state.lastOutcome.finished.snapshotHash)
          )

      // =========================================================================
      // COLLECTING SIGNATURES → COLLECTING BINARY SIGNATURES
      // =========================================================================

      private def advanceFromSignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeSignatures <- maybeGetAllDeclarations(state, resources)(_.signature)
          maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
          _ <- maybeSignatures.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          maybeGlobalOrd = extractGlobalSnapshotOrdinal(maybeFacilities)
          result <- (maybeGlobalOrd, maybeSignatures) match {
            case (Some(globalOrd), Some(signatures)) =>
              HasherSelector[F].withCurrent { implicit hs =>
                toBinarySignaturesPhase(state, status, globalOrd, signatures)
              }
            case _ =>
              none[Transition].pure[F]
          }
        } yield result

      private def extractGlobalSnapshotOrdinal(maybeFacilities: Option[SortedMap[PeerId, Facility]]): Option[SnapshotOrdinal] =
        maybeFacilities
          .map(_.values.map(_.lastGlobalSnapshotOrdinal).toList)
          .flatMap(pickMajority(_))

      private def toBinarySignaturesPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        globalOrdinal: SnapshotOrdinal,
        signatures: SortedMap[PeerId, MajoritySignature]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val proofs = signatures.map { case (id, sig) => SignatureProof(PeerId._Id.get(id), sig.signature) }.toList

        for {
          valid <- proofs.filterA(verifySignatureProof(status.majorityArtifactInfo.hash, _))
          _ <- logInvalidSignatures(state.key, proofs.size, valid.size)
          result <- buildBinaryTransition(state, status, valid, globalOrdinal)
        } yield result
      }

      private def buildBinaryTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        validSignatures: List[SignatureProof],
        globalOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        state.facilitators.value.hash.flatMap { facilitatorsHash =>
          NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
            val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signaturesNes)
            val stakingAddress = fetchStakingAddress(state.lastOutcome.finished.context.snapshotInfo)

            stateChannelSnapshotService
              .createBinary(signedArtifact, state.lastOutcome.finished.binaryArtifactHash, globalOrdinal.some, stakingAddress)
              .map { signedBinary =>
                Transition(
                  newState = state.copy(status =
                    CollectingBinarySignatures(
                      signedArtifact,
                      status.majorityArtifactInfo.context,
                      signedBinary.value,
                      status.majorityTrigger,
                      status.candidates,
                      facilitatorsHash,
                      state.lastOutcome.finished.snapshotHash
                    )
                  ),
                  sideEffect = spreadBinarySignature(
                    state.key,
                    signedBinary.proofs.head.signature,
                    facilitatorsHash,
                    state.lastOutcome.finished.snapshotHash
                  )
                )
              }
          }
        }

      // =========================================================================
      // COLLECTING BINARY SIGNATURES → FINISHED
      // =========================================================================

      private def advanceFromBinarySignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeBinarySignatures <- maybeGetAllDeclarations(state, resources)(_.binarySignature)
          _ <- maybeBinarySignatures.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          result <- maybeBinarySignatures.flatTraverse(toFinishedPhase(state, status, _))
        } yield result

      private def toFinishedPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        signatures: SortedMap[PeerId, BinarySignature]
      ): F[Option[Transition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          val proofs = signatures.map { case (id, bs) => SignatureProof(PeerId._Id.get(id), bs.signature) }.toList

          for {
            binaryHash <- status.binary.hash
            valid <- proofs.filterA(verifySignatureProof(binaryHash, _))
            _ <- logInvalidBinarySignatures(state.key, proofs.size, valid.size)
            result <- buildFinishedTransition(state, status, valid)
          } yield result
        }

      private def buildFinishedTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        validSignatures: List[SignatureProof]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        for {
          facilitatorsHash <- state.facilitators.value.hash
          snapshotHash <- status.signedMajorityArtifact.hash

          result <- NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
            val finalSignedBinary = Signed(status.binary, signaturesNes)
            finalSignedBinary.toHashed.map { hashedBinary =>
              Transition(
                newState = state.copy(status =
                  Finished(
                    status.signedMajorityArtifact,
                    hashedBinary.hash,
                    status.context,
                    status.majorityTrigger,
                    status.candidates,
                    facilitatorsHash,
                    snapshotHash
                  )
                ),
                sideEffect = persistAndGossip(status.signedMajorityArtifact, hashedBinary, state, status.context)
              )
            }
          }
        } yield result

      private def hashFacilitators(state: CurrencySnapshotConsensusState): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => state.facilitators.value.hash)

      private def hashArtifact(artifact: CurrencySnapshotArtifact): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => artifact.hash)

      private def extractEvents(peerEvents: Map[PeerId, List[(Ordinal, CurrencySnapshotEvent)]]): Set[CurrencySnapshotEvent] =
        peerEvents.values.flatten.map(_._2).toSet

      private def createArtifact(
        state: CurrencySnapshotConsensusState,
        trigger: ConsensusTrigger,
        events: Set[CurrencySnapshotEvent]
      )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] =
        consensusFns.createProposalArtifact(
          state.key,
          state.lastOutcome.finished.signedMajorityArtifact,
          state.lastOutcome.finished.context,
          hasher,
          trigger,
          events,
          state.facilitators.value.toSet,
          getGlobalSnapshotByOrdinal
        )

      private def storeReturnedEvents(
        peerEvents: Map[PeerId, List[(Ordinal, CurrencySnapshotEvent)]],
        returnedEvents: Set[CurrencySnapshotEvent]
      ): F[Unit] = {
        val filtered = peerEvents.map { case (pid, evts) => (pid, evts.filter { case (_, e) => returnedEvents.contains(e) }) }
          .filter(_._2.nonEmpty)
        consensusStorage.addEvents(filtered)
      }

      private def spreadProposal(
        key: CurrencySnapshotKey,
        hash: Hash,
        facilitatorsHash: Hash,
        artifact: CurrencySnapshotArtifact,
        lastSnapshotHash: Hash
      ): F[Unit] =
        gossip.spread(ConsensusPeerDeclaration(key, Proposal(hash, facilitatorsHash, lastSnapshotHash))) >>
          gossip.spreadCommon(ConsensusArtifact(key, artifact))

      private def spreadSignature(key: CurrencySnapshotKey, signature: Signature, facilitatorsHash: Hash, lastSnapshotHash: Hash): F[Unit] =
        gossip.spread(ConsensusPeerDeclaration(key, MajoritySignature(signature, facilitatorsHash, lastSnapshotHash)))

      private def spreadBinarySignature(
        key: CurrencySnapshotKey,
        signature: Signature,
        facilitatorsHash: Hash,
        lastSnapshotHash: Hash
      ): F[Unit] =
        gossip.spread(ConsensusPeerDeclaration(key, BinarySignature(signature, facilitatorsHash, lastSnapshotHash)))

      private def persistAndGossip(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        hashedBinary: Hashed[StateChannelSnapshotBinary],
        state: CurrencySnapshotConsensusState,
        context: CurrencySnapshotContext
      )(implicit hasher: Hasher[F]): F[Unit] =
        stateChannelSnapshotService.consume(
          signedArtifact,
          hashedBinary,
          state.lastOutcome.facilitators.value,
          context
        ) >>
          recordMetrics(signedArtifact, hashedBinary, context) >>
          gossipForkInfo(gossip, signedArtifact) >>
          notifyDataApplication(signedArtifact)

      private def recordMetrics(
        signed: Signed[CurrencySnapshotArtifact],
        hashedBinary: Hashed[StateChannelSnapshotBinary],
        context: CurrencySnapshotContext
      ): F[Unit] = {
        val metagraphTag: Metrics.TagSeq =
          Seq((Metrics.unsafeLabelName("metagraph_address"), context.address.show))

        // Blocks & transactions
        val allTransactions = signed.blocks.toList.flatMap(_.block.transactions.toList)
        val txCount = allTransactions.size
        val txAmountTotal = allTransactions.map(_.amount.value.value).sum
        val txFeeTotal = allTransactions.map(_.fee.value.value).sum

        // Rewards
        val rewardsCount = signed.rewards.size
        val rewardsAmountTotal = signed.rewards.toList.map(_.amount.value.value).sum

        // Tips
        val activeTips = signed.tips.remainedActive.size + signed.blocks.size
        val deprecatedTips = signed.tips.deprecated.size

        // Extended fields
        val messagesCount = signed.messages.map(_.size).getOrElse(0)
        val globalSnapshotSyncsCount = signed.globalSnapshotSyncs.map(_.size).getOrElse(0)
        val artifactsCount = signed.artifacts.map(_.size).getOrElse(0)

        // Fee transactions
        val feeTxList = signed.feeTransactions.map(_.toList).getOrElse(List.empty)
        val feeTransactionsCount = feeTxList.size
        val feeTransactionsAmountTotal = feeTxList.map(_.amount.value.value).sum

        // AllowSpend
        val allowSpendBlocks = signed.allowSpendBlocks.map(_.toList).getOrElse(List.empty)
        val allowSpendBlocksCount = allowSpendBlocks.size
        val allAllowSpends = allowSpendBlocks.flatMap(_.transactions.toList)
        val allowSpendTxCount = allAllowSpends.size
        val allowSpendAmountTotal = allAllowSpends.map(_.amount.value.value).sum
        val allowSpendFeeTotal = allAllowSpends.map(_.fee.value.value).sum

        // TokenLock
        val tokenLockBlocks = signed.tokenLockBlocks.map(_.toList).getOrElse(List.empty)
        val tokenLockBlocksCount = tokenLockBlocks.size
        val allTokenLocks = tokenLockBlocks.flatMap(_.tokenLocks.toList)
        val tokenLockTxCount = allTokenLocks.size
        val tokenLockAmountTotal = allTokenLocks.map(_.amount.value.value).sum
        val tokenLockFeeTotal = allTokenLocks.map(_.fee.value.value).sum

        // Data application
        val dataAppOnChainStateBytes = signed.dataApplication.map(_.onChainState.length.toLong).getOrElse(0L)
        val dataAppBlocksCount = signed.dataApplication.map(_.blocks.size).getOrElse(0)
        val dataAppBlocksTotalBytes = signed.dataApplication.map(_.blocks.map(_.length.toLong).sum).getOrElse(0L)

        // Binary
        val binaryContentBytes = hashedBinary.content.length.toLong
        val binaryFee = hashedBinary.fee.value.value

        Metrics[F].updateGauge("dag_currency_snapshot_ordinal", signed.ordinal.value) >>
          Metrics[F].updateGauge("dag_currency_snapshot_height", signed.height.value) >>
          Metrics[F].updateGauge("dag_currency_snapshot_signature_count", signed.proofs.size) >>
          // Cumulative counters for value metrics (survive across scrapes unlike gauges)
          Metrics[F].incrementCounterBy("dag_currency_snapshot_blocks_total", signed.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transactions_total", txCount) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transaction_amount_cumulative", txAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transaction_fee_cumulative", txFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_rewards_amount_cumulative", rewardsAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_fee_transactions_amount_cumulative", feeTransactionsAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_allow_spend_amount_cumulative", allowSpendAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_allow_spend_fee_cumulative", allowSpendFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_token_lock_amount_cumulative", tokenLockAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_token_lock_fee_cumulative", tokenLockFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_binary_fee_cumulative", binaryFee) >>
          // Blocks & transactions - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_blocks_count", signed.blocks.size) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transactions_count", txCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transaction_amount_total", txAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transaction_fee_total", txFeeTotal) >>
          // Rewards - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_rewards_count", rewardsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_rewards_amount_total", rewardsAmountTotal) >>
          // Tips
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_tips_count", activeTips, Seq(("tip_type", "active"))) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_tips_count", deprecatedTips, Seq(("tip_type", "deprecated"))) >>
          // Extended fields
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_messages_count", messagesCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_global_snapshot_syncs_count", globalSnapshotSyncsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_artifacts_count", artifactsCount) >>
          // Fee transactions - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_fee_transactions_count", feeTransactionsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_fee_transactions_amount_total", feeTransactionsAmountTotal) >>
          // AllowSpend - counts, amounts, fees
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_blocks_count", allowSpendBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_tx_count", allowSpendTxCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_amount_total", allowSpendAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_fee_total", allowSpendFeeTotal) >>
          // TokenLock - counts, amounts, fees
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_blocks_count", tokenLockBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_tx_count", tokenLockTxCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_amount_total", tokenLockAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_fee_total", tokenLockFeeTotal) >>
          // Data application sizes
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_onchain_state_bytes", dataAppOnChainStateBytes) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_blocks_count", dataAppBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_blocks_total_bytes", dataAppBlocksTotalBytes) >>
          // Binary size and fee
          Metrics[F].updateGauge("dag_currency_snapshot_binary_content_bytes", binaryContentBytes) >>
          Metrics[F].updateGauge("dag_currency_snapshot_binary_fee", binaryFee)
      }

      private def notifyDataApplication(signedArtifact: Signed[CurrencySnapshotArtifact]): F[Unit] =
        maybeDataApplication.traverse_ { da =>
          HasherSelector[F].withCurrent(implicit h => signedArtifact.toHashed) >>= da.onSnapshotConsensusResult
        }.handleErrorWith(logger.error(_)("Unhandled exception during onSnapshotConsensusResult"))

      private def checkForkByLastSnapshotHash[A](declarations: SortedMap[PeerId, A], ownHash: Hash)(
        implicit extract: A => Hash
      ): F[Unit] =
        recoverIfForking[F](ownHash, lastSnapshotHashObservationName, restartService, nodeStorage, leavingDelay)(
          declarations.map { case (pid, decl) => (pid, extract(decl)) }
        )

      private implicit val extractFacilityHash: Facility => Hash = _.lastSnapshotHash
      private implicit val extractProposalHash: Proposal => Hash = _.lastSnapshotHash
      private implicit val extractSignatureHash: MajoritySignature => Hash = _.lastSnapshotHash
      private implicit val extractBinarySignatureHash: BinarySignature => Hash = _.lastSnapshotHash

      private def clearTimeTriggerIfNeeded(trigger: ConsensusTrigger): F[Unit] =
        Applicative[F].whenA(trigger === TimeTrigger)(consensusStorage.clearTimeTrigger)

      private def recordProposalAffinity(allHashes: List[Hash], ownHash: Hash): F[Unit] =
        Metrics[F].recordDistribution("dag_consensus_proposal_affinity", proposalAffinity(allHashes, ownHash))

      private def logInvalidSignatures(key: CurrencySnapshotKey, total: Int, valid: Int): F[Unit] =
        logger
          .warn(s"Removed ${total - valid} invalid signatures for key=${key.show}, $valid valid remaining")
          .whenA(total != valid)

      private def logInvalidBinarySignatures(key: CurrencySnapshotKey, total: Int, valid: Int): F[Unit] =
        logger
          .warn(s"Removed ${total - valid} invalid binary signatures for key=${key.show}, $valid valid remaining")
          .whenA(total != valid)
    }
}
