package io.constellationnetwork.currency.l0.snapshot

import cats.Monad
import cats.effect.kernel.Clock
import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.schema.{CollectingFacilities, CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{MessageOrdinal, MessageType, fetchOwnerAddress}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}

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

  def make[F[_]: Async](
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    consensusStorage: CurrencyConsensusStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    feeCalculator: FeeCalculator[F],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]],
    metagraphId: Address
  ): CurrencySnapshotConsensusStateCreator[F] = new CurrencySnapshotConsensusStateCreator[F] {
    private val logger = Slf4jLogger.getLogger[F]

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
        consensusStorage.existsEvent(isInitialOwnerMessageEvent(metagraphId))
      ).ifM(
        consensusStorage
          .condModifyState(key)(toCreateStateFn(facilitateConsensus(key, lastOutcome, maybeTrigger, resources)))
          .flatMap(evalEffect)
          .flatTap(logIfCreatedState),
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
    ): F[(CurrencySnapshotConsensusState, F[Unit])] =
      for {

        candidates <- consensusStorage.getCandidates(key.next)

        facilitators <- lastOutcome.facilitators.value
          .concat(lastOutcome.finished.candidates.value)
          .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))
          .filterA(consensusFns.facilitatorFilter(lastOutcome.finished.signedMajorityArtifact, lastOutcome.finished.context, _))
          .map(_.prepended(selfId).distinct.sorted)

        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(CurrencyConsensusKind.Facility)
        }

        time <- Clock[F].monotonic
        lastGlobalSnapshotOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(bound, candidates, maybeTrigger, lastOutcome.finished.facilitatorsHash, lastGlobalSnapshotOrdinal)
            )
          )
        }
        state = ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
          key,
          lastOutcome,
          Facilitators(remained),
          CollectingFacilities(
            maybeTrigger,
            lastOutcome.finished.facilitatorsHash
          ),
          time,
          withdrawnFacilitators = WithdrawnFacilitators(withdrawn.toSet),
          spreadAckKinds = Set.empty
        )
      } yield (state, effect)
  }
}
