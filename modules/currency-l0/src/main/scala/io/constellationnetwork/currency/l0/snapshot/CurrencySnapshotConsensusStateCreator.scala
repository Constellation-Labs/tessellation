package io.constellationnetwork.currency.l0.snapshot

import cats.Monad
import cats.effect.kernel.Clock
import cats.effect.syntax.all._
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration.{AttemptDomain, Facility}
import io.constellationnetwork.currency.l0.snapshot.synchronous.message.ConsensusPeerDeclaration
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{MessageOrdinal, MessageType, fetchOwnerAddress}
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusStateCreator[F[_]: Sync]
    extends ConsensusStateCreator[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

object CurrencySnapshotConsensusStateCreator {

  private val hashOrdering: Ordering[Hash] = cats.Order[Hash].toOrdering

  private val availabilityRequestTimeout = 5.seconds

  /** Each member advertises only its deterministic share of the fixed proposal work budget. The complete Facility union is defensively
    * capped to the same bound by the advancer, so declarations and availability work remain bounded as N grows.
    */
  private[snapshot] def facilityEventLimit(committeeSize: Int): Int =
    math.max(1, EventMempool.DefaultSnapshotLimit / math.max(1, committeeSize))

  /** Keeps only events that have a confirmed copy on every round-start facilitator.
    *
    * Currency event data is transported independently from Facility declarations. Without this barrier, a node can advertise a hash and
    * crash after only part of the committee received the corresponding event: some nodes advance while others wait forever, and ordinary
    * Facility ACKs incorrectly classify the crashed advertiser as responsive. A confirmed hash remains retrievable after any one member
    * fails. A failed confirmation merely defers that event to a later snapshot; it never prevents an otherwise-empty round from starting.
    */
  private[snapshot] val availabilityProbeParallelism: Int = 8

  private[snapshot] def retainUniversallyAvailableHashes[F[_]: Async](
    localHashes: Set[Hash],
    peers: List[PeerId],
    maxParallelism: Int = availabilityProbeParallelism,
    deadline: FiniteDuration = availabilityRequestTimeout
  )(
    confirm: (PeerId, Set[Hash]) => F[Set[Hash]]
  ): F[SortedSet[Hash]] =
    if (localHashes.isEmpty) SortedSet.empty[Hash](hashOrdering).pure[F]
    else
      fs2.Stream
        .emits(peers)
        .covary[F]
        .parEvalMap(math.max(1, maxParallelism))(peerId => confirm(peerId, localHashes))
        .compile
        .toList
        .timeoutTo(deadline, List(Set.empty[Hash]).pure[F])
        .map(_.foldLeft(localHashes)(_ intersect _))
        .map(hashes => SortedSet.from(hashes)(hashOrdering))

  /** Applies the deterministic seedlist/collateral eligibility boundary before a registration may enter a Facility. */
  private[snapshot] def retainEligibleCandidates[F[_]: Monad](
    registered: Set[PeerId],
    seedlistAllows: PeerId => Boolean
  )(
    parentAllows: PeerId => F[Boolean]
  ): F[Candidates] =
    registered.toList.sorted
      .filter(seedlistAllows)
      .filterA(parentAllows)
      .map(peers => Candidates(peers.toSet))

  val InitialOwnerMessageOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(2L)

  /** The initial Owner message can only be accepted at snapshot ordinal 2 and the global L0 charges snapshot fees to the owner address from
    * that ordinal on. Producing snapshot 2 without the Owner message dooms every subsequent snapshot to rejection, so the round must not
    * start until the message is available for inclusion.
    */
  def canStartOwnedConsensus[F[_]: Monad](
    key: CurrencySnapshotKey,
    maybeOwnerAddress: Option[Address],
    getLastGlobalSnapshotOrdinal: F[Option[SnapshotOrdinal]],
    feeCalculator: FeeCalculator[F],
    pendingOwnerMessageExists: F[Boolean]
  ): F[Boolean] =
    if (key =!= InitialOwnerMessageOrdinal || maybeOwnerAddress.isDefined)
      true.pure[F]
    else
      getLastGlobalSnapshotOrdinal
        .map(_.map(feeCalculator.isFeeRequired).getOrElse(feeCalculator.isFeeRequired(SnapshotOrdinal.unsafeApply(Long.MaxValue))))
        .ifM(pendingOwnerMessageExists, true.pure[F])

  /** Matches the initial Owner message for this metagraph: an Owner message whose parent ordinal is the minimum, i.e. the one that takes
    * the special ordinal-2 acceptance path (see `CurrencySnapshotAcceptanceManager`). A type-only `Owner` match could instead be satisfied
    * by an Owner event for a different metagraph or by a non-initial Owner message with the wrong parent ordinal, which would open the gate
    * but be rejected by message acceptance and allow ordinal 2 to finalize without an accepted Owner.
    */
  def isInitialOwnerMessageEvent(
    metagraphId: Address
  ): CurrencySnapshotEvent => Boolean = {
    case CurrencyMessageEvent(message) =>
      message.messageType === MessageType.Owner &&
      message.metagraphId === metagraphId &&
      message.parentOrdinal === MessageOrdinal.MinValue
    case _ => false
  }

  def make[F[_]: Async: HasherSelector: Metrics](
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    consensusStorage: CurrencyConsensusStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    feeCalculator: FeeCalculator[F],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]],
    getMetagraphId: F[Address],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    clusterStorage: ClusterStorage[F],
    eventGossipClient: EventGossipClient[F, CurrencySnapshotEvent]
  ): CurrencySnapshotConsensusStateCreator[F] = new CurrencySnapshotConsensusStateCreator[F] {
    private val logger = Slf4jLogger.getLogger[F]

    private def recordAvailability(peerId: PeerId, outcome: String): F[Unit] =
      Metrics[F].incrementCounter(
        "dag_currency_consensus_event_availability_confirmation_total",
        Seq(
          Metrics.unsafeLabelName("peer_id") -> peerId.show,
          Metrics.unsafeLabelName("outcome") -> outcome
        )
      )

    private def confirmPeerHasEvents(
      peerId: PeerId,
      requested: Set[Hash]
    ): F[Set[Hash]] =
      clusterStorage.getPeer(peerId).flatMap {
        case None =>
          recordAvailability(peerId, "peer_unavailable") >>
            logger.warn(s"Deferring Currency events because round-start facilitator ${peerId.show} is unavailable").as(Set.empty)
        case Some(peer) =>
          eventGossipClient
            .getIHaveFor(IWantRequest(requested))
            .run(Peer.toP2PContext(peer))
            .timeout(availabilityRequestTimeout)
            .flatMap { ihave =>
              val confirmed = requested.intersect(ihave.hashes)
              recordAvailability(peerId, if (confirmed.size === requested.size) "confirmed" else "partial").as(confirmed)
            }
            .handleErrorWith { error =>
              recordAvailability(peerId, "request_failed") >>
                logger.warn(error)(s"Deferring Currency events that could not be confirmed on facilitator ${peerId.show}").as(Set.empty)
            }
      }

    private def facilityEventHashes(roundStartFacilitators: List[PeerId]): F[SortedSet[Hash]] =
      for {
        localSnapshot <- eventMempool.snapshot(facilityEventLimit(roundStartFacilitators.size))
        localHashes = localSnapshot.hashes
        peers = roundStartFacilitators.filterNot(_ === selfId)
        confirmed <- retainUniversallyAvailableHashes(localHashes, peers) { (peerId, requested) =>
          confirmPeerHasEvents(peerId, requested)
        }
        _ <- logger
          .info(
            s"Deferred ${localHashes.size - confirmed.size} Currency events that were not confirmed on every round-start facilitator"
          )
          .whenA(confirmed.size < localHashes.size)
        _ <- Metrics[F].updateGauge(
          "dag_currency_consensus_facility_event_deferred",
          (localHashes.size - confirmed.size).toLong
        )
      } yield confirmed

    def tryFacilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
    ): F[StateCreateResult] =
      canStartOwnedConsensus(
        key,
        fetchOwnerAddress(lastOutcome.finished.context.snapshotInfo),
        lastGlobalSnapshotStorage.getOrdinal,
        feeCalculator,
        getMetagraphId.flatMap { id =>
          eventMempool.snapshot().map(_.events.exists(event => isInitialOwnerMessageEvent(id)(event.signed.value)))
        }
      ).ifM(
        consensusStorage.runRetainedEffect(key) >>
          consensusStorage
            .condModifyStateWithEffect(key) {
              case None =>
                facilitateConsensus(key, lastOutcome, maybeTrigger, resources).map(
                  _.map { case (state, effect) => (state.some, state.some, effect) }
                )
              case Some(_) => none[(Option[CurrencySnapshotConsensusState], Option[CurrencySnapshotConsensusState], F[Unit])].pure[F]
            }
            .map(_.flatten)
            .flatTap(_ => consensusStorage.runRetainedEffect(key))
            .flatTap(logIfCreatedState(_)),
        logger
          .warn(
            s"Deferring consensus for key ${key.show}: waiting for the initial Owner message to set the metagraph owner " +
              s"before creating the first fee-paying snapshot, otherwise it gets rejected by the global L0"
          )
          .as(none)
      )

    private def facilitateConsensus(
      key: CurrencySnapshotKey,
      lastOutcome: CurrencyConsensusOutcome,
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
    ): F[Option[(CurrencySnapshotConsensusState, F[Unit])]] =
      for {

        registeredCandidates <- consensusStorage.getCandidates(key.next)
        // A registration proves only that the peer reached Observing. Filter it against the
        // same signed parent/seedlist eligibility used when the next committee is created;
        // otherwise an ineligible peer can be carried as a candidate, accept the private
        // hand-off, and then remain WaitingForReady forever when StateCreator filters it out.
        eligibleCandidates <- retainEligibleCandidates(
          registeredCandidates.value,
          peerId => seedlist.forall(_.map(_.peerId).contains(peerId))
        )(consensusFns.facilitatorFilter(lastOutcome.finished.signedMajorityArtifact, lastOutcome.finished.context, _))

        roundStartFacilitators <- lastOutcome.facilitators.value
          .concat(lastOutcome.finished.candidates.value)
          .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))
          .filterA(consensusFns.facilitatorFilter(lastOutcome.finished.signedMajorityArtifact, lastOutcome.finished.context, _))
          .map(_.distinct.sorted)

        (withdrawn, remained) = roundStartFacilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(CurrencyConsensusKind.Facility)
        }

        time <- Clock[F].monotonic
        // The declaration domain is fixed before applying asynchronous withdrawal
        // intent. Every honest node therefore emits the same domain whether the
        // withdrawal arrived just before or just after state creation.
        roundStartFacilitatorSet = SortedSet.from(roundStartFacilitators)
        facilitatorsHash <- HasherSelector[F].withCurrent(implicit hasher => roundStartFacilitatorSet.hash)
        parentArtifactHash <- HasherSelector[F].withCurrent(implicit hasher => lastOutcome.finished.signedMajorityArtifact.hash)
        lastGlobalSnapshotOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
        eventHashes <- facilityEventHashes(remained)
        facility = Facility(
          eventHashes,
          eligibleCandidates,
          maybeTrigger,
          lastGlobalSnapshotOrdinal,
          AttemptDomain(facilitatorsHash, parentArtifactHash, lastOutcome.finished.binaryArtifactHash)
        )
        result = Option.when(remained.contains(selfId)) {
          val effect = consensusStorage.retainAttemptDomain(key, facility.domain) >>
            consensusStorage.addFacility(selfId, key, facility, facility.domain.some) >>
            gossip.spread(ConsensusPeerDeclaration(key, facility))
          val state = ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
            key,
            lastOutcome,
            Facilitators(remained),
            CollectingFacilities(
              maybeTrigger,
              facilitatorsHash
            ),
            time,
            withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
            spreadAckKinds = Set.empty
          )
          (state, effect)
        }
        _ <- logger
          .debug(s"Skipping Currency facilitation for key=${key.show}: self is not in the flat committee")
          .whenA(result.isEmpty)
      } yield result
  }
}
