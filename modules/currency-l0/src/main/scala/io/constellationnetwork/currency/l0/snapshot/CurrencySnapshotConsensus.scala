package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipClient
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.verifySignatureProof
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}

import io.circe.{Decoder, Encoder}
import org.http4s.client.Client

/** Currency L0's deliberately flat, fully synchronous consensus engine.
  *
  * This is a Currency-local transliteration of the stable release/mainnet protocol: Facilities -> Proposals -> artifact signatures ->
  * binary signatures -> Finished. GL0's leaders, views, QCs, tiers, admission/eviction certificates, and quorum shrink are intentionally
  * absent. Currency committees are small permissioned deployments and retain the established fixed-universe ACK removal behavior.
  */
object CurrencySnapshotConsensus {

  /** Validate the private hand-off needed by the release/mainnet observe-and-register workflow.
    *
    * The downloaded artifact/context remain the public authority. The private outcome is accepted only from a responsive signer of that
    * exact artifact and contributes the flat committee metadata needed to enter the next round. The retained committee may be a strict
    * subset of artifact signers when a member disappears during the subsequent binary-signature phase. A newly registered validator is
    * expected in `finished.candidates`, not in the committee that signed the downloaded artifact, so either membership position authorizes
    * the local hand-off.
    */
  private[snapshot] def validateObservedOutcome[F[_]: Async: SecurityProvider](
    selfId: PeerId,
    outcome: CurrencyConsensusOutcome,
    key: CurrencySnapshotKey,
    publicArtifact: Signed[CurrencySnapshotArtifact],
    publicContext: CurrencySnapshotContext
  )(implicit hasher: io.constellationnetwork.security.Hasher[F]): F[Boolean] = {
    val privateArtifact = outcome.finished.signedMajorityArtifact
    val proofs = privateArtifact.proofs.toSortedSet.toList
    val signerIds = proofs.map(_.id.toPeerId)
    val facilitators = outcome.facilitators.value.toSet
    val candidates = outcome.finished.candidates.value
    val excluded = outcome.removedFacilitators.value.union(outcome.withdrawnFacilitators.value)
    val cursorWellFormed = candidates.isEmpty || outcome.finished.candidateCursor.exists(candidates.contains)

    for {
      artifactHash <- privateArtifact.value.hash
      signaturesValid <- proofs.traverse(verifySignatureProof(artifactHash, _)).map(_.forall(identity))
      facilitatorsHash <- outcome.facilitators.value.hash
    } yield
      outcome.key === key &&
        privateArtifact.value === publicArtifact.value &&
        privateArtifact.proofs === publicArtifact.proofs &&
        outcome.finished.context === publicContext &&
        signaturesValid &&
        signerIds.distinct.size === signerIds.size &&
        facilitators.nonEmpty &&
        facilitators.subsetOf(signerIds.toSet) &&
        (facilitators.contains(selfId) || candidates.contains(selfId)) &&
        excluded.intersect(facilitators).isEmpty &&
        excluded.intersect(candidates).isEmpty &&
        candidates.intersect(facilitators).isEmpty &&
        cursorWellFormed &&
        outcome.finished.facilitatorsHash === facilitatorsHash
  }

  def make[F[_]: Async: SecurityProvider: Metrics](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    getCurrencyAddress: F[Address],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    effectiveConsensusConfig: ConsensusConfig,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    feeCalculator: FeeCalculator[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    creator: CurrencySnapshotCreator[F],
    validator: CurrencySnapshotValidator[F],
    hasherSelector: HasherSelector[F],
    restartService: RestartService[F, _],
    leavingDelay: FiniteDuration,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey]
  )(
    implicit supervisor: Supervisor[F]
  ): F[CurrencySnapshotConsensus[F]] = {
    implicit val dataTransactionDecoder: Decoder[DataTransaction] = DataTransactionCodecs.decoder(maybeDataApplication)
    implicit val dataTransactionEncoder: Encoder[DataTransaction] = DataTransactionCodecs.encoder(maybeDataApplication)
    implicit val hs: HasherSelector[F] = hasherSelector

    for {
      consensusStorage <- synchronous.ConsensusStorage.make[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](effectiveConsensusConfig)

      consensusFunctions = CurrencySnapshotConsensusFunctions.make[F](
        collateral,
        maybeRewards,
        creator,
        validator,
        maybeCustomArtifacts
      )

      eventGossipClient = EventGossipClient.make[F, CurrencySnapshotEvent](client, session)

      consensusStateAdvancer = CurrencySnapshotConsensusStateAdvancer.make[F](
        effectiveConsensusConfig,
        selfId,
        keyPair,
        consensusStorage,
        consensusFunctions,
        stateChannelSnapshotService,
        gossip,
        maybeDataApplication,
        restartService,
        nodeStorage,
        leavingDelay,
        getGlobalSnapshotByOrdinal,
        seedlist,
        eventMempool
      )

      consensusStateCreator = CurrencySnapshotConsensusStateCreator.make[F](
        consensusFunctions,
        consensusStorage,
        lastGlobalSnapshotStorage,
        feeCalculator,
        gossip,
        selfId,
        seedlist,
        getCurrencyAddress,
        eventMempool,
        clusterStorage,
        eventGossipClient
      )

      consensusStateRemover = CurrencySnapshotConsensusStateRemover.make[F](consensusStorage, gossip)
      consensusStatusOps = CurrencySnapshotConsensusOps.make
      attemptDomain = (state: CurrencySnapshotConsensusState) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          state.lastOutcome.finished.signedMajorityArtifact.hash.map { parentArtifactHash =>
            synchronous.declaration.AttemptDomain(
              CurrencySnapshotConsensusOps.attemptFacilitatorsHash(state.status),
              parentArtifactHash,
              state.lastOutcome.finished.binaryArtifactHash
            )
          }
        }
      stateUpdater = synchronous.ConsensusStateUpdater.make(
        consensusStateAdvancer,
        consensusStorage,
        gossip,
        consensusStatusOps,
        attemptDomain
      )
      consensusClient = synchronous.ConsensusClient.make[F, CurrencySnapshotKey, CurrencyConsensusOutcome](client, session)
      validateObservedOutcome = (
        outcome: CurrencyConsensusOutcome,
        key: CurrencySnapshotKey,
        publicArtifact: Signed[CurrencySnapshotArtifact],
        publicContext: CurrencySnapshotContext
      ) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          CurrencySnapshotConsensus.validateObservedOutcome(selfId, outcome, key, publicArtifact, publicContext)
        }
      isAuthorizedForNextRound = (outcome: CurrencyConsensusOutcome) =>
        outcome.facilitators.value.contains(selfId) || outcome.finished.candidates.value.contains(selfId)
      nextRoundAuthority = (outcome: CurrencyConsensusOutcome) => outcome.facilitators.value.toSet.union(outcome.finished.candidates.value)
      manager <- synchronous.ConsensusManager.make(
        selfId,
        effectiveConsensusConfig,
        consensusStorage,
        consensusStateCreator,
        stateUpdater,
        consensusStateAdvancer,
        consensusStateRemover,
        consensusStatusOps,
        nodeStorage,
        clusterStorage,
        consensusClient,
        validateObservedOutcome,
        isAuthorizedForNextRound,
        nextRoundAuthority
      )
      routes = new synchronous.ConsensusRoutes(consensusStorage)
      handler = CurrencyConsensusHandler.make(consensusStorage, manager)
    } yield
      new synchronous.Consensus(
        handler,
        consensusStorage,
        manager,
        routes,
        consensusFunctions,
        manager.facilitateOnEvent.some
      )
  }
}
